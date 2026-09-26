package org.pms.patientservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.PatientServiceApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A developer's existing patient_db already has the patients table (created by the old data.sql
 * init script) but no Flyway history. Starting the real application against such a database must
 * baseline it at V1 instead of re-running V1 (which would fail on the existing table).
 */
@Testcontainers
class FlywayBaselineTest {

  /** The table exactly as the removed data.sql script created it. */
  private static final String LEGACY_DDL =
      """
      CREATE TABLE IF NOT EXISTS patients
      (
          id              UUID PRIMARY KEY,
          name            VARCHAR(255)        NOT NULL,
          email           VARCHAR(255) UNIQUE NOT NULL,
          address         VARCHAR(255)        NOT NULL,
          date_of_birth   DATE                NOT NULL,
          registered_date DATE                NOT NULL
      )
      """;

  private static final String INSERT_PATIENT =
      "INSERT INTO patients (id, name, email, address, date_of_birth, registered_date)"
          + " VALUES (gen_random_uuid(), ?, ?, '1 Legacy St', DATE '1990-01-01',"
          + " DATE '2024-01-01')";

  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5");

  @Test
  @DisplayName("app startup baselines a legacy schema at V1 without re-running V1")
  void legacySchemaIsBaselined() {
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    jdbc.execute(LEGACY_DDL);
    jdbc.update(INSERT_PATIENT, "Legacy One", "legacy-one@example.com");
    jdbc.update(INSERT_PATIENT, "Legacy Two", "legacy-two@example.com");

    // Uses the service's real application.properties; command-line args outrank it, so only
    // the connection is overridden. Startup would fail if V1 ran against the existing table.
    try (ConfigurableApplicationContext ignored =
        new SpringApplicationBuilder(PatientServiceApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())) {
      // Startup also ran Hibernate schema validation against the legacy table.
    }

    List<Map<String, Object>> history =
        jdbc.queryForList(
            "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(history)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("version")).isEqualTo("1");
              assertThat(row.get("type")).isEqualTo("BASELINE");
              assertThat(row.get("success")).isEqualTo(true);
            });

    List<String> emails =
        jdbc.queryForList("SELECT email FROM patients ORDER BY email", String.class);
    assertThat(emails).containsExactly("legacy-one@example.com", "legacy-two@example.com");
  }
}
