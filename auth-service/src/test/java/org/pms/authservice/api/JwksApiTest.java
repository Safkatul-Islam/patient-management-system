package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.authservice.support.TestRsaKeys;

class JwksApiTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("JWKS publishes the RSA public key and none of its private members")
  void jwksHasOnlyPublicMembers() throws Exception {
    mockMvc
        .perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys.length()").value(1))
        .andExpect(jsonPath("$.keys[0].kid").value(TestRsaKeys.KEY_ID))
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
        .andExpect(jsonPath("$.keys[0].use").value("sig"))
        .andExpect(jsonPath("$.keys[0].n").isString())
        .andExpect(jsonPath("$.keys[0].e").isString())
        .andExpect(jsonPath("$.keys[0].d").doesNotExist())
        .andExpect(jsonPath("$.keys[0].p").doesNotExist())
        .andExpect(jsonPath("$.keys[0].q").doesNotExist())
        .andExpect(jsonPath("$.keys[0].dp").doesNotExist())
        .andExpect(jsonPath("$.keys[0].dq").doesNotExist())
        .andExpect(jsonPath("$.keys[0].qi").doesNotExist());
  }

  @Test
  @DisplayName("An issued access token verifies against the published JWK")
  void issuedTokenVerifiesAgainstJwks() throws Exception {
    String jwks =
        mockMvc
            .perform(get("/.well-known/jwks.json"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = JsonPath.read(newDoctorSession(), "$.accessToken");

    SignedJWT jwt = SignedJWT.parse(accessToken);
    RSAKey published = (RSAKey) JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID());

    assertThat(published).isNotNull();
    assertThat(published.isPrivate()).isFalse();
    assertThat(jwt.verify(new RSASSAVerifier(published))).isTrue();
  }
}
