package org.pms.analyticsservice.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Throwaway Postgres and Redpanda matching docker-compose. Registered as beans so their lifecycle
 * follows the cached Spring context: one pair of containers for the whole test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

  public static final String SOURCE_TOPIC = "patient.created";
  public static final int SOURCE_PARTITIONS = 3;

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:17.5");
  }

  @Bean
  @ServiceConnection
  RedpandaContainer redpandaContainer() {
    return new RedpandaContainer("redpandadata/redpanda:v26.2.2");
  }

  /**
   * The producer's topic as the contract declares it (3 partitions). The producer service owns it;
   * this test creates it so partition-specific behaviour runs against the real layout.
   */
  @Bean
  NewTopic patientCreatedTopic() {
    return TopicBuilder.name(SOURCE_TOPIC).partitions(SOURCE_PARTITIONS).build();
  }
}
