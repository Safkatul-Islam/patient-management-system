package org.pms.authservice.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.pms.authservice.exception.InvalidAccountRequestException;

/** Email normalization and password rules shared by account creation and login. */
final class Credentials {

  static final int PASSWORD_MIN_CHARS = 12;
  static final int PASSWORD_MAX_CHARS = 72;

  /** BCrypt only reads 72 bytes; Spring Security 7's encoder rejects longer input outright. */
  static final int PASSWORD_MAX_UTF8_BYTES = 72;

  private Credentials() {}

  /** Emails are stored and looked up trimmed and lower-cased, so matching is case-insensitive. */
  static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  static boolean fitsBcrypt(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_UTF8_BYTES;
  }

  /**
   * The service's own guarantee, independent of how the caller validated its input: the encoder is
   * never handed a password it would reject (the DTOs can only bound characters, not bytes).
   */
  static void requireValidPassword(String password) {
    if (password == null
        || password.length() < PASSWORD_MIN_CHARS
        || password.length() > PASSWORD_MAX_CHARS) {
      throw new InvalidAccountRequestException(
          "password", "size must be between " + PASSWORD_MIN_CHARS + " and " + PASSWORD_MAX_CHARS);
    }
    if (!fitsBcrypt(password)) {
      throw new InvalidAccountRequestException(
          "password", "must be at most " + PASSWORD_MAX_UTF8_BYTES + " bytes when UTF-8 encoded");
    }
  }
}
