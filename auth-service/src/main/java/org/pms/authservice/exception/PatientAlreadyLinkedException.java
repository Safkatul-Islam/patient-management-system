package org.pms.authservice.exception;

/** The patient already has a login account. The message never contains the patient id. */
public class PatientAlreadyLinkedException extends RuntimeException {

  public PatientAlreadyLinkedException() {
    super("Patient already has an account");
  }
}
