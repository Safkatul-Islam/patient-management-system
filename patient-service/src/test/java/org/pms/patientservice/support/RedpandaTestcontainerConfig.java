package org.pms.patientservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Supplies a throwaway Kafka-compatible broker (the Redpanda version docker-compose runs). Every
 * full-application test imports it next to {@link PostgresTestcontainerConfig}: without it the
 * service would publish to the default bootstrap address, i.e. a developer's local broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaTestcontainerConfig {

  @Bean
  @ServiceConnection
  RedpandaContainer redpandaContainer() {
    return new RedpandaContainer("redpandadata/redpanda:v26.2.2");
  }
}
