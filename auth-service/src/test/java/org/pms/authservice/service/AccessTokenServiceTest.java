package org.pms.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.authservice.config.JwtProperties;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.support.TestRsaKeys;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AccessTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00.750Z");
  private static final Instant NOW_SECONDS = Instant.parse("2026-03-01T10:00:00Z");

  private final JwtProperties properties =
      new JwtProperties(
          "http://auth-service:4005",
          "pms-api",
          TestRsaKeys.KEY_ID,
          TestRsaKeys.signingPrivateKeyBase64(),
          Duration.ofMinutes(15));

  private final AccessTokenService service =
      new AccessTokenService(
          new NimbusJwtEncoder(
              new ImmutableJWKSet<>(
                  new JWKSet(
                      new RSAKey.Builder(TestRsaKeys.signingPublicKey())
                          .privateKey(TestRsaKeys.signingPrivateKey())
                          .keyID(TestRsaKeys.KEY_ID)
                          .build()))),
          properties,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("Header is RS256 with the configured kid and typ JWT; signature verifies")
  void headerAndSignature() throws Exception {
    SignedJWT jwt = SignedJWT.parse(service.issue(user(Role.DOCTOR, null)));

    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo(TestRsaKeys.KEY_ID);
    assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
    assertThat(jwt.verify(new RSASSAVerifier(TestRsaKeys.signingPublicKey()))).isTrue();
  }

  @Test
  @DisplayName("Staff token carries exactly iss, sub, aud, roles, iat, exp, jti")
  void staffClaims() throws Exception {
    User doctor = user(Role.DOCTOR, null);
    SignedJWT jwt = SignedJWT.parse(service.issue(doctor));
    JWTClaimsSet claims = jwt.getJWTClaimsSet();

    assertThat(claims.getClaims().keySet())
        .containsExactlyInAnyOrder("iss", "sub", "aud", "roles", "iat", "exp", "jti");
    assertThat(claims.getIssuer()).isEqualTo("http://auth-service:4005");
    assertThat(claims.getSubject()).isEqualTo(doctor.getId().toString());
    assertThat(claims.getAudience()).containsExactly("pms-api");
    assertThat(claims.getStringListClaim("roles")).containsExactly("DOCTOR");
    assertThat(claims.getIssueTime()).isEqualTo(Date.from(NOW_SECONDS));
    assertThat(claims.getExpirationTime())
        .isEqualTo(Date.from(NOW_SECONDS.plus(Duration.ofMinutes(15))));
    assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
  }

  @Test
  @DisplayName("roles is always a JSON array; a single aud is a string (RFC 7519 4.1.3)")
  void rolesArrayAndAudienceForm() throws Exception {
    Map<String, Object> payload =
        SignedJWT.parse(service.issue(user(Role.NURSE, null))).getPayload().toJSONObject();

    assertThat(payload.get("roles")).isEqualTo(List.of("NURSE"));
    // Nimbus writes a one-element audience as a plain string, which RFC 7519 allows and every
    // conforming verifier (including Spring's JwtAudienceValidator) reads as a one-element list.
    assertThat(payload.get("aud")).isEqualTo("pms-api");
  }

  @Test
  @DisplayName("PATIENT token carries patientId")
  void patientClaims() throws Exception {
    UUID patientId = UUID.randomUUID();
    JWTClaimsSet claims =
        SignedJWT.parse(service.issue(user(Role.PATIENT, patientId))).getJWTClaimsSet();

    assertThat(claims.getStringClaim("patientId")).isEqualTo(patientId.toString());
    assertThat(claims.getStringListClaim("roles")).containsExactly("PATIENT");
  }

  @Test
  @DisplayName("Every token gets a fresh jti")
  void jtiIsUnique() throws Exception {
    User user = user(Role.ADMIN, null);
    String first = SignedJWT.parse(service.issue(user)).getJWTClaimsSet().getJWTID();
    String second = SignedJWT.parse(service.issue(user)).getJWTClaimsSet().getJWTID();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("TTL is reported as 900 seconds")
  void ttlSeconds() {
    assertThat(service.ttlSeconds()).isEqualTo(900);
  }

  static User user(Role role, UUID patientId) {
    User user = new User("someone@pms.test", "hash", role, patientId, NOW);
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    return user;
  }
}
