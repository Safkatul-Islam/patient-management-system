package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Flyway owns the schema; the context starting at all proves ddl-auto=validate accepted it. */
class SchemaMigrationTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("Flyway applied V1 and V2 successfully")
  void flywayAppliedBothMigrations() {
    List<String> versions =
        jdbcTemplate.queryForList(
            "select version from flyway_schema_history where success order by installed_rank",
            String.class);
    assertThat(versions).containsExactly("1", "2");
  }

  @Test
  @DisplayName("DB rejects a staff role linked to a patient")
  void checkRejectsStaffWithPatientId() {
    assertThatThrownBy(() -> insertUser("DOCTOR", UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_patient_link_check");
  }

  @Test
  @DisplayName("DB rejects a PATIENT account without a patient id")
  void checkRejectsPatientWithoutPatientId() {
    assertThatThrownBy(() -> insertUser("PATIENT", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_patient_link_check");
  }

  @Test
  @DisplayName("DB rejects an unknown role")
  void checkRejectsUnknownRole() {
    assertThatThrownBy(() -> insertUser("JANITOR", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("users_role_check");
  }

  private void insertUser(String role, UUID patientId) {
    jdbcTemplate.update(
        "insert into users (id, email, password_hash, role, patient_id, created_at)"
            + " values (?, ?, 'not-a-real-hash', ?, ?, now())",
        UUID.randomUUID(),
        uniqueEmail(),
        role,
        patientId);
  }
}
