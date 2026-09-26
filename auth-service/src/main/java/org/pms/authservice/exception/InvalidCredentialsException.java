package org.pms.authservice.exception;

/**
 * Login failed. Deliberately does not say why: unknown email, wrong password and inactive account
 * are indistinguishable to the caller.
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("Invalid credentials");
  }
}
