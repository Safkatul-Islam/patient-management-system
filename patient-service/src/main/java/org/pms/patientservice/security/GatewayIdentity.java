package org.pms.patientservice.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The caller as verified by the API gateway: its user id, its single role and, for a PATIENT, the
 * id of the patient record it owns. Framework-free so the service layer can take it as a plain
 * argument.
 *
 * <p>Exactly one role per caller is the V1 contract; holding a single {@link Role} makes that
 * structural, so no combination such as PATIENT plus a staff role can ever be represented.
 *
 * @param userId the access token's subject
 * @param role the caller's only role
 * @param patientId present exactly when {@code role} is {@link Role#PATIENT}
 */
public record GatewayIdentity(UUID userId, Role role, UUID patientId) {

  public GatewayIdentity {
    Objects.requireNonNull(userId, "userId is required");
    Objects.requireNonNull(role, "role is required");
    if ((role == Role.PATIENT) != (patientId != null)) {
      throw new IllegalArgumentException("A patient id is required exactly for the PATIENT role");
    }
  }

  /** A PATIENT may only access its own patient record. */
  public boolean isPatient() {
    return role == Role.PATIENT;
  }

  /**
   * Leaves out the ids: framework debug logging prints the authenticated principal, and a patient
   * id links a log line to a patient record.
   */
  @Override
  public String toString() {
    return "GatewayIdentity[role=" + role + "]";
  }
}
