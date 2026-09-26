package org.pms.patientservice.exception;

/** The message is fixed on purpose: an email address is PII and must not reach logs. */
public class EmailAlreadyExistsException extends RuntimeException {
  public EmailAlreadyExistsException() {
    super("A patient with this email already exists");
  }
}
