package org.pms.authservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Access-token signing settings. Validated at startup so a missing key or key id stops the service
 * instead of failing on the first login.
 *
 * @param issuer value of the {@code iss} claim (not secret)
 * @param audience value placed in the {@code aud} claim and required on verification (not secret)
 * @param keyId {@code kid} header value; also the id of the published JWK
 * @param privateKey base64 (standard alphabet, one line) of a DER-encoded PKCS#8 RSA private key;
 *     secret, supplied only through the environment
 * @param accessTokenTtl lifetime of an access token
 */
@Validated
@ConfigurationProperties("pms.auth.jwt")
public record JwtProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotBlank String keyId,
    @NotBlank String privateKey,
    @NotNull Duration accessTokenTtl) {

  @Override
  public String toString() {
    // Keep the key out of any accidental log line or failure-analysis output.
    return "JwtProperties[issuer=%s, audience=%s, keyId=%s, accessTokenTtl=%s]"
        .formatted(issuer, audience, keyId, accessTokenTtl);
  }
}
