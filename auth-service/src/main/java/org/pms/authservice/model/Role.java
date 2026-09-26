package org.pms.authservice.model;

/** The single role a user holds. Carried in the access token's {@code roles} claim. */
public enum Role {
  ADMIN,
  DOCTOR,
  NURSE,
  BILLING_STAFF,
  PATIENT;

  /** Staff roles are created via the staff endpoint; PATIENT accounts have their own. */
  public boolean isStaff() {
    return this != PATIENT;
  }
}
