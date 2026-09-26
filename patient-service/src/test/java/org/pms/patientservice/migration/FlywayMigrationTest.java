package org.pms.patientservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fresh databases get their schema from Flyway's V1 migration, not from an init script. */
@SpringBootTest
@Import(PostgresTestcontainerConfig.class)
class FlywayMigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("V1 migration is applied on a fresh database")
  void v1MigrationIsApplied() {
    List<Map<String, Object>> history =
        jdbcTemplate.queryForList(
            "SELECT version, type, script, success FROM flyway_schema_history ORDER BY"
                + " installed_rank");

    assertThat(history)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("version")).isEqualTo("1");
              assertThat(row.get("type")).isEqualTo("SQL");
              assertThat(row.get("script")).isEqualTo("V1__create_patients_table.sql");
              assertThat(row.get("success")).isEqualTo(true);
            });
  }

  @Test
  @DisplayName("V1 migration seeds no patient rows")
  void v1MigrationSeedsNoRows() {
    Integer seeded =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM patients WHERE email NOT LIKE 'regression-%'", Integer.class);

    assertThat(seeded).isZero();
  }

  @Test
  @DisplayName("the email unique constraint has the name the exception handler relies on")
  void emailUniqueConstraintIsNamed() {
    List<String> uniqueConstraints =
        jdbcTemplate.queryForList(
            "SELECT constraint_name FROM information_schema.table_constraints"
                + " WHERE table_name = 'patients' AND constraint_type = 'UNIQUE'",
            String.class);

    assertThat(uniqueConstraints).containsExactly("patients_email_key");
  }
}
