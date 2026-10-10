package org.pms.patientservice.config;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.pms.patientservice.event.PatientCreatedTopicProperties;
import org.pms.patientservice.event.PatientEventPublisherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.ProducerListener;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  PatientCreatedTopicProperties.class,
  PatientEventPublisherProperties.class
})
public class PatientEventsConfig {

  /**
   * Boot's KafkaAdmin creates this topic at startup when it is missing. Creation is attempted once,
   * at startup, and not retried: if the broker is unreachable then, KafkaAdmin logs the failure
   * after {@code spring.kafka.admin.operation-timeout}, startup completes and the service stays
   * ready, but sends fail until the topic exists. Recover by restarting the service once the broker
   * is up, or by creating the topic ({@code rpk topic create patient.created -p 3}).
   */
  @Bean
  NewTopic patientCreatedTopic(PatientCreatedTopicProperties properties) {
    return TopicBuilder.name(properties.topic())
        .partitions(properties.partitions())
        .replicas(properties.replicas())
        .build();
  }

  /**
   * Replaces Boot's default {@code LoggingProducerListener}, which on every failed send logs the
   * record's key and payload plus a stack trace. PatientCreatedPublisher logs each failure itself,
   * once, with identifiers only, so this listener does nothing. Typed {@code <Object, Object>}
   * because that is what Boot's KafkaTemplate bean method injects.
   */
  @Bean
  ProducerListener<Object, Object> kafkaProducerListener() {
    return new ProducerListener<>() {};
  }

  /** Source of event timestamps; a bean so tests can pin time. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
