package org.pms.apigateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway settings, validated at startup so a bad URI or timeout stops the gateway instead of
 * failing on the first request. Nothing here is secret.
 *
 * @param authServiceUri base URI of auth-service (scheme, host and port only; paths are not
 *     rewritten)
 * @param patientServiceUri base URI of patient-service
 * @param jwt access-token verification settings
 */
@Validated
@ConfigurationProperties("pms.gateway")
public record GatewayProperties(
    @NotNull URI authServiceUri, @NotNull URI patientServiceUri, @NotNull @Valid Jwt jwt) {

  public GatewayProperties {
    requireHttpUri("pms.gateway.auth-service-uri", authServiceUri);
    requireHttpUri("pms.gateway.patient-service-uri", patientServiceUri);
  }

  /**
   * @param issuer required {@code iss} claim value
   * @param audience value the {@code aud} claim must contain
   * @param jwks where and how the signing keys are fetched
   */
  public record Jwt(
      @NotBlank String issuer, @NotBlank String audience, @NotNull @Valid Jwks jwks) {}

  /**
   * @param uri the only location keys are ever fetched from (token headers such as {@code jku} are
   *     never followed)
   * @param cooldown minimum time between two fetches; bounds how often unknown key ids can make the
   *     gateway call auth-service
   * @param connectTimeout TCP connect timeout of a fetch
   * @param responseTimeout time allowed for the response of a fetch
   * @param maxSize largest JWK set response accepted
   */
  public record Jwks(
      @NotNull URI uri,
      @NotNull Duration cooldown,
      @NotNull Duration connectTimeout,
      @NotNull Duration responseTimeout,
      @NotNull DataSize maxSize) {

    public Jwks {
      requireHttpUri("pms.gateway.jwt.jwks.uri", uri);
      requirePositive("pms.gateway.jwt.jwks.cooldown", cooldown);
      requirePositive("pms.gateway.jwt.jwks.connect-timeout", connectTimeout);
      requirePositive("pms.gateway.jwt.jwks.response-timeout", responseTimeout);
      if (maxSize != null && maxSize.toBytes() <= 0) {
        throw new IllegalArgumentException("pms.gateway.jwt.jwks.max-size must be positive");
      }
    }
  }

  private static void requireHttpUri(String name, URI uri) {
    if (uri == null) {
      return; // reported by @NotNull
    }
    boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
    if (!http || uri.getHost() == null) {
      throw new IllegalArgumentException(name + " must be an absolute http(s) URI with a host");
    }
  }

  private static void requirePositive(String name, Duration duration) {
    if (duration != null && (duration.isZero() || duration.isNegative())) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
