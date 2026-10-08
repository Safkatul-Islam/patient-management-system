package org.pms.notificationservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.pms.notificationservice.exception.InvalidEventException;
import org.pms.notificationservice.messaging.ObservedDeadLetterPublishingRecoverer;
import org.pms.notificationservice.messaging.PatientCreatedEventReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Failure handling for the patient.created listener. Boot applies the single {@code
 * CommonErrorHandler} bean to its listener container factory, so the listener needs no wiring.
 *
 * <p>A failing record is retried a bounded number of times (the effect may hit a transient database
 * problem), then dead-lettered. Records that can never succeed (not JSON, wrong type or version,
 * missing fields) skip the retries and are dead-lettered on the first failure. Either way the
 * offset then moves on, so one bad record cannot block its partition.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PatientCreatedConsumerProperties.class)
public class PatientCreatedConsumerConfig {

  static final String DEAD_LETTERED_METRIC = "pms.events.dead_lettered";

  /**
   * Declared by this service because it owns the topic (one per consumer group). Same partition
   * count as the source or more, since dead-lettered records keep their source partition number.
   */
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
    return new ObservedDeadLetterPublishingRecoverer(
        kafkaTemplate, properties.deadLetterTopic(), reader, deadLettered);
  }

  @Bean
  DefaultErrorHandler patientCreatedErrorHandler(
      ObservedDeadLetterPublishingRecoverer recoverer,
      PatientCreatedConsumerProperties properties) {
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(properties.retryInterval().toMillis(), properties.maxRetries()));
    errorHandler.addNotRetryableExceptions(InvalidEventException.class);
    return errorHandler;
  }
}
