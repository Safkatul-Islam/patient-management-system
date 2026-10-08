package org.pms.patientservice.config;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.pms.patientservice.event.PatientCreatedTopicProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PatientCreatedTopicProperties.class)
public class PatientEventsConfig {

  /**
   * Boot's KafkaAdmin creates this topic at startup when it is missing. If the broker is down then,
   * startup still completes: KafkaAdmin logs the failure and the service stays ready.
   */
  @Bean
  NewTopic patientCreatedTopic(PatientCreatedTopicProperties properties) {
    return TopicBuilder.name(properties.topic())
        .partitions(properties.partitions())
        .replicas(properties.replicas())
        .build();
  }

  /** Source of event timestamps; a bean so tests can pin time. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
