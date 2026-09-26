package org.pms.patientservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
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
 * init script) but no Flyway history. By default the application must refuse to start against such
 * a database; with the documented one-time baseline flags it must baseline it at V1 instead of
 * re-running V1.
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

  /** The one-time flags documented in application.properties. */
  private static final String[] BASELINE_FLAGS = {
    "--spring.flyway.baseline-on-migrate=true", "--spring.flyway.baseline-version=1"
  };

  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5");

  private JdbcTemplate jdbc;

  /** Every test starts from a pre-Flyway legacy database: the table and rows, no history. */
  @BeforeEach
  void createLegacyDatabase() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    jdbc.execute(LEGACY_DDL);
    jdbc.update(INSERT_PATIENT, "Legacy One", "legacy-one@example.com");
    jdbc.update(INSERT_PATIENT, "Legacy Two", "legacy-two@example.com");
  }

  @Test
  @DisplayName("by default, startup fails on a non-empty schema with no Flyway history")
  void startupFailsWithoutBaselineFlags() {
    Throwable thrown = catchThrowable(this::startApplication);

    assertThat(thrown).hasRootCauseInstanceOf(FlywayException.class);
    assertThat(thrown).rootCause().hasMessageContaining("no schema history table");
    assertThat(historyTableExists()).isFalse();
    assertThat(legacyEmails()).containsExactly("legacy-one@example.com", "legacy-two@example.com");
  }

  @Test
  @DisplayName("the one-time baseline flags baseline the legacy schema at V1 without running V1")
  void baselineFlagsBaselineLegacySchema() {
    // Startup would fail if V1 ran against the existing table. It also runs Hibernate schema
    // validation against the legacy table.
    startApplication(BASELINE_FLAGS);

    assertThat(history())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("version")).isEqualTo("1");
              assertThat(row.get("type")).isEqualTo("BASELINE");
              assertThat(row.get("success")).isEqualTo(true);
            });
    assertThat(legacyEmails()).containsExactly("legacy-one@example.com", "legacy-two@example.com");
    // The 409 mapping for concurrent duplicate emails relies on this constraint name.
    assertThat(
            jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints"
                    + " WHERE table_name = 'patients' AND constraint_type = 'UNIQUE'",
                String.class))
        .containsExactly("patients_email_key");
  }

  @Test
  @DisplayName("after the one-time baseline run, later startups need no flags")
  void laterStartupsNeedNoFlags() {
    startApplication(BASELINE_FLAGS);

    startApplication();

    assertThat(history())
        .singleElement()
        .satisfies(row -> assertThat(row.get("type")).isEqualTo("BASELINE"));
  }

  /**
   * Starts and stops the real application with its own application.properties. Command-line args
   * outrank that file, so only the connection (plus any extra flags) is overridden.
   */
  private void startApplication(String... extraArgs) {
    String[] connection = {
      "--spring.datasource.url=" + postgres.getJdbcUrl(),
      "--spring.datasource.username=" + postgres.getUsername(),
      "--spring.datasource.password=" + postgres.getPassword()
    };
    String[] args =
        Stream.concat(Stream.of(connection), Stream.of(extraArgs)).toArray(String[]::new);
    try (ConfigurableApplicationContext ignored =
        new SpringApplicationBuilder(PatientServiceApplication.class)
            .web(WebApplicationType.NONE)
            .run(args)) {
      // Only startup matters.
    }
  }

  private List<Map<String, Object>> history() {
    return jdbc.queryForList(
        "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank");
  }

  private boolean historyTableExists() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean.class));
  }

  private List<String> legacyEmails() {
    return jdbc.queryForList("SELECT email FROM patients ORDER BY email", String.class);
  }
}
