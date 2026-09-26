package org.pms.authservice.exception;

/**
 * An account request broke a business rule that bean validation cannot express on its own (role and
 * patientId pairing, the BCrypt byte limit). Rendered as a 400 validation problem for {@code
 * field}.
 */
public class InvalidAccountRequestException extends RuntimeException {

  private final String field;

  public InvalidAccountRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
