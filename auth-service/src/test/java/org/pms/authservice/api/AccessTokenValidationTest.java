package org.pms.authservice.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.authservice.support.TestRsaKeys;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Forged and invalid bearer tokens against a protected endpoint. Each case changes exactly one
 * thing relative to the valid control token, which must be accepted.
 */
class AccessTokenValidationTest extends AbstractAuthApiTest {

  private static final String PROTECTED = "/auth/logout";
  private static final String BODY = "{\"refreshToken\":\"irrelevant\"}";

  @Test
  @DisplayName("Control: a correctly signed, current token is accepted")
  void validTokenAccepted() throws Exception {
    postJson(PROTECTED, BODY, rs256(TestRsaKeys.SIGNING, UnaryOperator.identity()))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("Expired token is rejected")
  void expiredRejected() throws Exception {
    Instant past = Instant.now().minus(Duration.ofHours(1));
    assertRejected(
        rs256(
            TestRsaKeys.SIGNING,
            claims ->
                claims
                    .issueTime(Date.from(past))
                    .expirationTime(Date.from(past.plus(Duration.ofMinutes(15))))));
  }

  @Test
  @DisplayName("Wrong issuer is rejected")
  void wrongIssuerRejected() throws Exception {
    assertRejected(rs256(TestRsaKeys.SIGNING, claims -> claims.issuer("http://evil.example")));
  }

  @Test
  @DisplayName("Wrong audience is rejected")
  void wrongAudienceRejected() throws Exception {
    assertRejected(rs256(TestRsaKeys.SIGNING, claims -> claims.audience("some-other-api")));
  }

  @Test
  @DisplayName("Unsigned alg=none token is rejected")
  void algNoneRejected() throws Exception {
    assertRejected(new PlainJWT(validClaims().build()).serialize());
  }

  @Test
  @DisplayName("HS256 token keyed with the RSA public key bytes (algorithm confusion) is rejected")
  void algorithmConfusionRejected() throws Exception {
    JWSSigner hmac = new MACSigner(TestRsaKeys.signingPublicKey().getEncoded());
    assertRejected(sign(JWSAlgorithm.HS256, hmac, validClaims().build()));
  }

  @Test
  @DisplayName("RS256 token signed by a different key with the same kid is rejected")
  void foreignKeyWithSameKidRejected() throws Exception {
    KeyPair attackerKeys = TestRsaKeys.generate(2048);
    assertRejected(rs256(attackerKeys, UnaryOperator.identity()));
  }

  @Test
  @DisplayName("Garbage in the Authorization header is rejected")
  void malformedTokenRejected() throws Exception {
    assertRejected("not.a.jwt");
  }

  private void assertRejected(String token) throws Exception {
    postJson(PROTECTED, BODY, token)
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("A valid bearer access token is required."));
  }

  private static JWTClaimsSet.Builder validClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject(UUID.randomUUID().toString())
        .audience(List.of(AUDIENCE))
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
        .jwtID(UUID.randomUUID().toString())
        .claim("roles", List.of("DOCTOR"));
  }

  private static String rs256(KeyPair keys, UnaryOperator<JWTClaimsSet.Builder> tweak)
      throws JOSEException {
    return sign(
        JWSAlgorithm.RS256,
        new RSASSASigner(keys.getPrivate()),
        tweak.apply(validClaims()).build());
  }

  private static String sign(JWSAlgorithm algorithm, JWSSigner signer, JWTClaimsSet claims)
      throws JOSEException {
    JWSHeader header =
        new JWSHeader.Builder(algorithm).keyID(TestRsaKeys.KEY_ID).type(JOSEObjectType.JWT).build();
    SignedJWT jwt = new SignedJWT(header, claims);
    jwt.sign(signer);
    return jwt.serialize();
  }
}
