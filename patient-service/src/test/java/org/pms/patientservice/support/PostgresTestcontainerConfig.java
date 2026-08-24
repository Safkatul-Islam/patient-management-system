package org.pms.patientservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Supplies a throwaway Postgres for integration tests, matching the version the
 * service runs against in docker-compose. Registered as a bean so its lifecycle
 * follows the (cached) Spring context: one container for the whole test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17.5");
    }
}
