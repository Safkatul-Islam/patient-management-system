package org.pms.apigateway.security;

/** The roles auth-service can issue. A token naming any other role is rejected. */
public enum Role {
  ADMIN,
  DOCTOR,
  NURSE,
  BILLING_STAFF,
  PATIENT
}
