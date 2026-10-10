package org.pms.notificationservice.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Throwaway Postgres and Redpanda for integration tests, matching the versions in docker-compose.
 * Registered as beans so their lifecycle follows the (cached) Spring context: one pair of
 * containers for the whole test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersTestConfig {

  /** The contract's partition count for patient.created. */
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
   * In production patient-service (the producer) owns this topic. Tests create it with the
   * contract's layout before the listener starts, so partition-specific behaviour is real.
   */
  @Bean
  NewTopic patientCreatedTopic() {
    return TopicBuilder.name("patient.created").partitions(SOURCE_PARTITIONS).build();
  }
}
