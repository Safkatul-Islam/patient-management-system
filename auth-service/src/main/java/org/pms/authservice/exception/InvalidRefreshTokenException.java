package org.pms.authservice.exception;

/**
 * A refresh token was unknown, expired, already used (revoked) or belongs to an inactive user. The
 * caller is not told which.
 */
public class InvalidRefreshTokenException extends RuntimeException {

  public InvalidRefreshTokenException() {
    super("Invalid refresh token");
  }
}
