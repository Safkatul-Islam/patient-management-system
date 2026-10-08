package org.pms.notificationservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.pms.notificationservice.exception.InvalidEventException;
import org.pms.notificationservice.messaging.FailedAttemptLogger;
import org.pms.notificationservice.messaging.ObservedDeadLetterPublishingRecoverer;
import org.pms.notificationservice.messaging.PatientCreatedBackOffs;
import org.pms.notificationservice.messaging.PatientCreatedEventReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure handling for the patient.created listener. Boot applies the single {@code
 * CommonErrorHandler} bean to its listener container factory, so the listener needs no wiring.
 *
 * <ul>
 *   <li>Records that can never succeed (not JSON, wrong type or version, missing fields) are
 *       dead-lettered on the first failure, without retries.
 *   <li>While this service's database is unreachable, a record is retried with a long, bounded
 *       exponential back-off: an outage must not dead-letter valid events.
 *   <li>Any other failure is retried a few times, then dead-lettered.
 * </ul>
 *
 * Once a record is dead-lettered the offset moves on, so one bad record cannot block its partition.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PatientCreatedConsumerProperties.class)
public class PatientCreatedConsumerConfig {

  static final String DEAD_LETTERED_METRIC = "pms.events.dead_lettered";
  static final String DEAD_LETTER_FAILURES_METRIC = "pms.events.dead_letter.failures";

  /** Declared by this service because it owns the topic (one per consumer group). */
  @Bean
  NewTopic patientCreatedDeadLetterTopic(PatientCreatedConsumerProperties properties) {
    return TopicBuilder.name(properties.deadLetterTopic())
        .partitions(properties.deadLetterPartitions())
        .build();
  }

  @Bean
  ObservedDeadLetterPublishingRecoverer patientCreatedDeadLetterRecoverer(
      KafkaTemplate<Object, Object> kafkaTemplate,
      PatientCreatedConsumerProperties properties,
      PatientCreatedEventReader reader,
      MeterRegistry meterRegistry,
      @Value("${spring.application.name}") String consumerName) {
    Counter deadLettered =
        Counter.builder(DEAD_LETTERED_METRIC)
            .description(
                "Records this consumer could not process and sent to its dead-letter topic")
            .tag("consumer", consumerName)
            .register(meterRegistry);
    Counter publicationFailures =
        Counter.builder(DEAD_LETTER_FAILURES_METRIC)
            .description("Failed attempts to publish a record to this consumer's dead-letter topic")
            .tag("consumer", consumerName)
            .register(meterRegistry);
    return new ObservedDeadLetterPublishingRecoverer(
        kafkaTemplate, properties.deadLetterTopic(), reader, deadLettered, publicationFailures);
  }

  @Bean
  DefaultErrorHandler patientCreatedErrorHandler(
      ObservedDeadLetterPublishingRecoverer recoverer,
      PatientCreatedConsumerProperties properties) {
    BackOff other =
        new FixedBackOff(properties.retryInterval().toMillis(), properties.maxRetries());
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, other);
    errorHandler.setBackOffFunction(
        new PatientCreatedBackOffs(connectivityBackOff(properties.connectivityRetry()), other));
    errorHandler.addNotRetryableExceptions(InvalidEventException.class);
    errorHandler.setRetryListeners(new FailedAttemptLogger());
    return errorHandler;
  }

  /** Max elapsed time counts the pauses between attempts, not the time the attempts take. */
  private static BackOff connectivityBackOff(
      PatientCreatedConsumerProperties.ConnectivityRetry retry) {
    ExponentialBackOff backOff =
        new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    backOff.setMaxElapsedTime(retry.maxElapsedTime().toMillis());
    return backOff;
  }
}
