package org.pms.authservice.exception;

/** An account with this email already exists. The message never contains the email. */
public class EmailAlreadyInUseException extends RuntimeException {

  public EmailAlreadyInUseException() {
    super("Email already in use");
  }
}
