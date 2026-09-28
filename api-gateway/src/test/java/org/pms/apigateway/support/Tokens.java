package org.pms.apigateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Builds synthetic access tokens shaped like auth-service's, with any header or claims. */
public final class Tokens {

  public static final String ISSUER = "http://auth-service:4005";
  public static final String AUDIENCE = "pms-api";
  public static final Duration TTL = Duration.ofMinutes(15);

  private Tokens() {}

  /** Claims of a valid staff token for {@code userId} with the given role. */
  public static JWTClaimsSet.Builder staffClaims(Clock clock, UUID userId, String role) {
    Instant now = clock.instant();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject(userId.toString())
        .audience(AUDIENCE)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(TTL)))
        .jwtID(UUID.randomUUID().toString())
        .claim("roles", List.of(role));
  }

  /** Claims of a valid PATIENT token linked to {@code patientId}. */
  public static JWTClaimsSet.Builder patientClaims(Clock clock, UUID userId, UUID patientId) {
    return staffClaims(clock, userId, "PATIENT").claim("patientId", patientId.toString());
  }

  /** The header auth-service writes: RS256, kid, typ JWT. */
  public static JWSHeader.Builder header(String kid) {
    return new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(JOSEObjectType.JWT);
  }

  public static String sign(RSAKey key, JWSHeader header, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Signed with {@code key}, using its kid in an otherwise standard header. */
  public static String sign(RSAKey key, JWTClaimsSet claims) {
    return sign(key, header(key.getKeyID()).build(), claims);
  }

  /** A valid DOCTOR token signed with the published primary key. */
  public static String validDoctor(Clock clock) {
    return sign(TestKeys.PRIMARY, staffClaims(clock, UUID.randomUUID(), "DOCTOR").build());
  }

  /** Raw compact serialization from literal JSON parts, for headers no library would produce. */
  public static String raw(String headerJson, String claimsJson, String signature) {
    return b64(headerJson) + "." + b64(claimsJson) + "." + signature;
  }

  public static String b64(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
