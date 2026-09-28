package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.TestKeys;
import org.pms.apigateway.support.Tokens;
import org.springframework.http.HttpHeaders;

/**
 * Forged, tampered and malformed tokens, each sent through a real protected route. Every one must
 * end as a 401 problem with nothing forwarded downstream.
 */
class JwtAttackTest extends AbstractGatewayTest {

  private JWTClaimsSet doctorClaims() {
    return Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").build();
  }

  @Test
  @DisplayName("Control: a valid token reaches the downstream")
  void validTokenAccepted() {
    Echo echo = echo(getPatients(bearer(Tokens.validDoctor(clock))));
    assertThat(echo.uri()).isEqualTo(PATIENTS);
  }

  @Test
  @DisplayName("alg=none: an unsigned PlainJWT is rejected")
  void algNoneUnsigned() {
    String token = new PlainJWT(doctorClaims()).serialize();
    assertRejected(getPatients(bearer(token)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "None", "NONE", "nOnE"})
  @DisplayName("alg=none variants with a garbage signature are rejected")
  void algNoneWithGarbageSignature(String alg) {
    String token =
        Tokens.raw(
            "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + TestKeys.PRIMARY_KID + "\"}",
            doctorClaims().toString(),
            "Z2FyYmFnZQ");
    assertRejected(getPatients(bearer(token)));
  }

  @Test
  @DisplayName("RS256 to HS256 confusion: HMAC-signed with the RSA public key bytes is rejected")
  void algorithmConfusion() throws Exception {
    byte[] publicKeyBytes = TestKeys.PRIMARY.toRSAPublicKey().getEncoded();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .keyID(TestKeys.PRIMARY_KID)
                .type(JOSEObjectType.JWT)
                .build(),
            doctorClaims());
    jwt.sign(new MACSigner(publicKeyBytes));
    assertRejected(getPatients(bearer(jwt.serialize())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../../etc/passwd", "' OR 1=1 --", "unknown-kid", "gw-test-key-1 "})
  @DisplayName("Manipulated kid values are rejected")
  void manipulatedKid(String kid) {
    String token = Tokens.sign(TestKeys.PRIMARY, Tokens.header(kid).build(), doctorClaims());
    assertRejected(getPatients(bearer(token)));
  }

  @Test
  @DisplayName("A very long kid is rejected")
  void veryLongKid() {
    String token =
        Tokens.sign(TestKeys.PRIMARY, Tokens.header("k".repeat(2000)).build(), doctorClaims());
    assertRejected(getPatients(bearer(token)));
  }

  @Test
  @DisplayName("An empty or missing kid is rejected")
  void emptyOrMissingKid() {
    assertRejected(
        getPatients(
            bearer(Tokens.sign(TestKeys.PRIMARY, Tokens.header("").build(), doctorClaims()))));
    JWSHeader noKid = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, noKid, doctorClaims()))));
  }

  @Test
  @DisplayName("A foreign RSA key reusing the published kid is rejected")
  void foreignKeySameKid() {
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.FOREIGN_SAME_KID, doctorClaims()))));
  }

  @Test
  @DisplayName("An embedded jwk header (attacker's own public key) is not trusted")
  void embeddedJwk() {
    JWSHeader ownKid =
        Tokens.header(TestKeys.ATTACKER.getKeyID()).jwk(TestKeys.ATTACKER.toPublicJWK()).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, ownKid, doctorClaims()))));
    JWSHeader ourKid =
        Tokens.header(TestKeys.PRIMARY_KID).jwk(TestKeys.ATTACKER.toPublicJWK()).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, ourKid, doctorClaims()))));
  }

  @Test
  @DisplayName("jku and x5u headers are never followed")
  void jkuAndX5uNeverFetched() {
    URI attacker = URI.create(stub.attackerUri());
    JWSHeader jku = Tokens.header(TestKeys.ATTACKER.getKeyID()).jwkURL(attacker).build();
    JWSHeader jkuOurKid = Tokens.header(TestKeys.PRIMARY_KID).jwkURL(attacker).build();
    JWSHeader x5u = Tokens.header(TestKeys.ATTACKER.getKeyID()).x509CertURL(attacker).build();

    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, jku, doctorClaims()))));
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, jkuOurKid, doctorClaims()))));
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, x5u, doctorClaims()))));
    assertThat(stub.attackerRequests()).isZero();
  }

  @Test
  @DisplayName("An x5c header is not trusted")
  void x5cNotTrusted() throws JOSEException {
    // Not a certificate, just attacker-chosen bytes: the gateway must never look at x5c at all.
    Base64 blob = Base64.encode(TestKeys.ATTACKER.toRSAPublicKey().getEncoded());
    JWSHeader x5c =
        Tokens.header(TestKeys.ATTACKER.getKeyID()).x509CertChain(List.of(blob)).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.ATTACKER, x5c, doctorClaims()))));
  }

  @Test
  @DisplayName("Wrong issuer is rejected")
  void wrongIssuer() {
    JWTClaimsSet claims =
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").issuer("http://evil").build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims))));
  }

  @Test
  @DisplayName("Wrong audience is rejected")
  void wrongAudience() {
    JWTClaimsSet claims =
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").audience("other-api").build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims))));
  }

  @Test
  @DisplayName("Expired token is rejected (beyond the 60s clock skew)")
  void expired() {
    JWTClaimsSet claims =
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR")
            .expirationTime(Date.from(clock.instant().minus(Duration.ofMinutes(2))))
            .build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims))));
  }

  @Test
  @DisplayName("A token accepted now is rejected once the clock passes its expiry")
  void expiresWithClock() {
    String token = Tokens.validDoctor(clock);
    echo(getPatients(bearer(token)));
    clock.advance(Tokens.TTL.plusMinutes(2));
    stub.clearReceived();
    assertRejected(getPatients(bearer(token)));
  }

  @Test
  @DisplayName("Future nbf is rejected")
  void futureNotBefore() {
    JWTClaimsSet claims =
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR")
            .notBeforeTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
            .build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims))));
  }

  @Test
  @DisplayName("Missing exp is rejected")
  void missingExpiry() {
    JWTClaimsSet claims =
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").expirationTime(null).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims))));
  }

  @Test
  @DisplayName("typ other than JWT, or missing, is rejected")
  void wrongOrMissingType() {
    JWSHeader atJwt =
        Tokens.header(TestKeys.PRIMARY_KID).type(new JOSEObjectType("at+jwt")).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, atJwt, doctorClaims()))));
    JWSHeader noType =
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestKeys.PRIMARY_KID).build();
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, noType, doctorClaims()))));
  }

  @Test
  @DisplayName("typ comparison is case-insensitive: lowercase jwt is accepted")
  void lowercaseTypeAccepted() {
    JWSHeader lower = Tokens.header(TestKeys.PRIMARY_KID).type(new JOSEObjectType("jwt")).build();
    echo(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, lower, doctorClaims()))));
  }

  @Test
  @DisplayName("A token in the query string is not accepted")
  void queryStringTokenIgnored() {
    String token = Tokens.validDoctor(clock);
    assertRejected(client.get().uri(PATIENTS + "?access_token=" + token).exchange());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer ", "Bearer", "Bearer not a token", "Bearer abc$%^", "Bearerx"})
  @DisplayName("Empty or malformed bearer values are rejected")
  void malformedBearer(String authorization) {
    assertRejected(getPatients(authorization));
  }

  @Test
  @DisplayName("A non-bearer scheme is rejected")
  void basicSchemeRejected() {
    assertRejected(getPatients("Basic dXNlcjpwYXNz"));
  }

  @Test
  @DisplayName("The scheme is case-insensitive: lowercase bearer is accepted")
  void lowercaseSchemeAccepted() {
    echo(getPatients("bearer " + Tokens.validDoctor(clock)));
  }

  @Test
  @DisplayName("Two Authorization headers are rejected")
  void multipleAuthorizationHeaders() {
    String token = Tokens.validDoctor(clock);
    assertRejected(
        client
            .get()
            .uri(PATIENTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(token), bearer("other"))
            .exchange());
  }

  @Test
  @DisplayName("A rejected token gets error=invalid_token; a missing one the bare scheme")
  void challenges() {
    getPatients(bearer("garbage"))
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    client
        .get()
        .uri(PATIENTS)
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    assertThat(stub.received()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sub-not-uuid",
        "sub-uppercase-uuid",
        "unknown-role",
        "roles-string",
        "roles-empty",
        "roles-missing",
        "roles-duplicate",
        "patient-without-patient-id",
        "patient-id-without-patient",
        "patient-id-not-uuid"
      })
  @DisplayName("Malformed identity claims are rejected before forwarding")
  void malformedIdentityClaims(String variant) {
    UUID userId = UUID.randomUUID();
    JWTClaimsSet.Builder claims = Tokens.staffClaims(clock, userId, "DOCTOR");
    switch (variant) {
      case "sub-not-uuid" -> claims.subject("admin");
      case "sub-uppercase-uuid" -> claims.subject(userId.toString().toUpperCase());
      case "unknown-role" -> claims.claim("roles", List.of("SUPERUSER"));
      case "roles-string" -> claims.claim("roles", "DOCTOR");
      case "roles-empty" -> claims.claim("roles", List.of());
      case "roles-missing" -> claims.claim("roles", null);
      case "roles-duplicate" -> claims.claim("roles", List.of("DOCTOR", "DOCTOR"));
      case "patient-without-patient-id" -> claims.claim("roles", List.of("PATIENT"));
      case "patient-id-without-patient" -> claims.claim("patientId", UUID.randomUUID().toString());
      case "patient-id-not-uuid" ->
          claims.claim("roles", List.of("PATIENT")).claim("patientId", "42");
      default -> throw new IllegalArgumentException(variant);
    }
    assertRejected(getPatients(bearer(Tokens.sign(TestKeys.PRIMARY, claims.build()))));
  }
}
