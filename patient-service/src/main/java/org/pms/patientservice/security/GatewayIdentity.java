package org.pms.patientservice.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The caller as verified by the API gateway: its user id, roles and, for a PATIENT, the id of the
 * patient record it owns. Framework-free so the service layer can take it as a plain argument.
 *
 * @param userId the access token's subject
 * @param roles never empty
 * @param patientId present exactly when {@code roles} contains {@link Role#PATIENT}
 */
public record GatewayIdentity(UUID userId, Set<Role> roles, UUID patientId) {

  public GatewayIdentity {
    Objects.requireNonNull(userId, "userId is required");
    roles = Set.copyOf(Objects.requireNonNull(roles, "roles are required"));
    if (roles.isEmpty()) {
      throw new IllegalArgumentException("At least one role is required");
    }
    if (roles.contains(Role.PATIENT) != (patientId != null)) {
      throw new IllegalArgumentException("A patient id is required exactly for the PATIENT role");
    }
  }

  /** A PATIENT may only access its own patient record. */
  public boolean isPatient() {
    return roles.contains(Role.PATIENT);
  }

  /**
   * Leaves out the ids: framework debug logging prints the authenticated principal, and a patient
   * id links a log line to a patient record.
   */
  @Override
  public String toString() {
    return "GatewayIdentity[roles=" + roles + "]";
  }
}
