package org.pms.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the first ADMIN account, supplied through the environment. Both blank means the
 * bootstrap is not configured. Deliberately not {@code @Validated}: a binding failure report would
 * print the rejected email; {@link BootstrapAdminRunner} validates without echoing values.
 */
@ConfigurationProperties("pms.auth.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password) {

  public boolean isConfigured() {
    return hasText(email) && hasText(password);
  }

  public boolean isPartiallyConfigured() {
    return hasText(email) != hasText(password);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  @Override
  public String toString() {
    return "BootstrapAdminProperties[email=<redacted>, password=<redacted>]";
  }
}
