package org.pms.patientservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayIdentityTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PATIENT_ID = UUID.randomUUID();

  @Test
  @DisplayName("a PATIENT identity requires a patient id")
  void patientRequiresPatientId() {
    assertThatThrownBy(() -> new GatewayIdentity(USER_ID, Role.PATIENT, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a staff identity must not carry a patient id")
  void staffRejectsPatientId() {
    assertThatThrownBy(() -> new GatewayIdentity(USER_ID, Role.DOCTOR, PATIENT_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an identity needs a role")
  void roleIsRequired() {
    assertThatThrownBy(() -> new GatewayIdentity(USER_ID, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("only the PATIENT role is a patient")
  void isPatientFollowsRole() {
    assertThat(new GatewayIdentity(USER_ID, Role.PATIENT, PATIENT_ID).isPatient()).isTrue();
    assertThat(new GatewayIdentity(USER_ID, Role.ADMIN, null).isPatient()).isFalse();
  }

  @Test
  @DisplayName("toString leaves out the user and patient ids")
  void toStringOmitsIds() {
    GatewayIdentity identity = new GatewayIdentity(USER_ID, Role.PATIENT, PATIENT_ID);

    assertThat(identity.toString())
        .contains("PATIENT")
        .doesNotContain(USER_ID.toString())
        .doesNotContain(PATIENT_ID.toString());
  }
}
