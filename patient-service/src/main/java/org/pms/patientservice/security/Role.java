package org.pms.patientservice.security;

/**
 * The roles a caller can hold, exactly as the gateway names them in {@code X-User-Roles}.
 *
 * <p>patient-service keeps its own copy instead of depending on auth-service: services share no
 * domain code. A role added in auth-service is unknown here until it is added here too, so a caller
 * holding it is rejected (fail closed) rather than silently granted anything.
 */
public enum Role {
  ADMIN,
  DOCTOR,
  NURSE,
  BILLING_STAFF,
  PATIENT
}
