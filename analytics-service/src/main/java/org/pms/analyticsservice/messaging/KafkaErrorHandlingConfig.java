package org.pms.analyticsservice.messaging;

import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure policy of the {@code patient.created} consumer. Boot's Kafka auto-configuration applies
 * the single {@link CommonErrorHandler} bean to the listener container factory.
 *
 * <ul>
 *   <li>A failing record is tried 3 times in total, 1 second apart, then dead-lettered. Retrying is
 *       safe: the handler's transaction rolled back, so no effect was half-applied.
 *   <li>An {@link InvalidEventException} (unparseable or not a supported v1 event) is dead-lettered
 *       on the first failure, because redelivering the same bytes cannot succeed.
 *   <li>Nothing is dropped: if the dead-letter publish fails, the record is kept and tried again.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfig {

  /** At least as many as {@code patient.created} has (3), so it can take the same load. */
  static final int DEAD_LETTER_PARTITIONS = 3;

  private static final long RETRY_INTERVAL_MS = 1000;
  private static final long RETRIES_AFTER_FIRST_ATTEMPT = 2;

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
    // Partition left unset (-1): the producer picks it from the record key (the patientId), so
    // one patient's dead letters stay in order, and the publish cannot fail because the source
    // topic gained partitions the dead-letter topic lacks. The original partition and offset
    // travel in the kafka_dlt-original-* headers.
    DeadLetterPublishingRecoverer publisher =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate, (record, exception) -> new TopicPartition(deadLetterTopic, -1));
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            new DeadLetterRecoverer(publisher, reader, meterRegistry, consumerGroup),
            new FixedBackOff(RETRY_INTERVAL_MS, RETRIES_AFTER_FIRST_ATTEMPT));
    errorHandler.addNotRetryableExceptions(InvalidEventException.class);
    return errorHandler;
  }
}
