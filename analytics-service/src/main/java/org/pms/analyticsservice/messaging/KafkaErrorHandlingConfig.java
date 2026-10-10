package org.pms.analyticsservice.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.pms.analyticsservice.event.InvalidEventException;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure policy of the {@code patient.created} consumer. Boot's Kafka auto-configuration applies
 * the single {@link CommonErrorHandler} bean to the listener container factory. Retrying is always
 * safe: the handler's transaction rolled back, so no effect was half-applied.
 *
 * <ul>
 *   <li>Database unreachable or transient ({@link ListenerFailures#isConnectivityFailure}): retried
 *       with exponential back-off (1 s doubling, at most 30 s apart) for up to 15 minutes, because
 *       the event itself is fine. Only then is it dead-lettered. The partition waits meanwhile.
 *   <li>An {@link InvalidEventException} (unparseable or not a supported v1 event): dead-lettered
 *       on the first failure, because redelivering the same bytes cannot succeed.
 *   <li>Anything else: tried 3 times in total, 1 second apart, then dead-lettered.
 *   <li>Nothing is dropped: if the dead-letter publish fails, the record is kept and tried again.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

  /** At least as many as {@code patient.created} has (3), so it can take the same load. */
  static final int DEAD_LETTER_PARTITIONS = 3;

  private static final long RETRY_INTERVAL_MS = 1000;
  private static final long RETRIES_AFTER_FIRST_ATTEMPT = 2;

  // Each wait stays far below max.poll.interval.ms (5 minutes by default), so the consumer keeps
  // its partitions while it waits for the database.
  private static final long CONNECTIVITY_INITIAL_INTERVAL_MS = 1000;
  private static final double CONNECTIVITY_MULTIPLIER = 2;
  private static final long CONNECTIVITY_MAX_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
  private static final long CONNECTIVITY_MAX_ELAPSED_MS = Duration.ofMinutes(15).toMillis();

  @Bean
  NewTopic patientCreatedDeadLetterTopic(
      @Value("${pms.events.patient-created.dead-letter-topic}") String deadLetterTopic) {
    return TopicBuilder.name(deadLetterTopic).partitions(DEAD_LETTER_PARTITIONS).build();
  }

  @Bean
  CommonErrorHandler patientCreatedErrorHandler(
      KafkaOperations<?, ?> kafkaTemplate,
      PatientCreatedEventReader reader,
      MeterRegistry meterRegistry,
      @Value("${pms.events.patient-created.dead-letter-topic}") String deadLetterTopic,
      @Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            new DeadLetterRecoverer(
                deadLetterPublisher(kafkaTemplate, deadLetterTopic),
                reader,
                meterRegistry,
                consumerGroup),
            new FixedBackOff(RETRY_INTERVAL_MS, RETRIES_AFTER_FIRST_ATTEMPT));
    errorHandler.addNotRetryableExceptions(InvalidEventException.class);
    BackOff connectivityBackOff = connectivityBackOff();
    // Returning null keeps the default (fixed) back-off for every other failure.
    errorHandler.setBackOffFunction(
        (record, exception) ->
            ListenerFailures.isConnectivityFailure(exception) ? connectivityBackOff : null);
    errorHandler.setRetryListeners(new FailedAttemptLogger());
    return errorHandler;
  }

  private static DeadLetterPublishingRecoverer deadLetterPublisher(
      KafkaOperations<?, ?> kafkaTemplate, String deadLetterTopic) {
    // Partition left unset (-1): the producer picks it from the record key (the patientId), so
    // one patient's dead letters stay in order, and the publish cannot fail because the source
    // topic gained partitions the dead-letter topic lacks. The original partition and offset
    // travel in the kafka_dlt-original-* headers.
    DeadLetterPublishingRecoverer publisher =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate, (record, exception) -> new TopicPartition(deadLetterTopic, -1));
    // Exception messages and stack traces are arbitrary text that any reader of the topic could
    // see; only the exception class names are kept.
    publisher.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
    return publisher;
  }

  private static BackOff connectivityBackOff() {
    ExponentialBackOff backOff =
        new ExponentialBackOff(CONNECTIVITY_INITIAL_INTERVAL_MS, CONNECTIVITY_MULTIPLIER);
    backOff.setMaxInterval(CONNECTIVITY_MAX_INTERVAL_MS);
    backOff.setMaxElapsedTime(CONNECTIVITY_MAX_ELAPSED_MS);
    return backOff;
  }
}
