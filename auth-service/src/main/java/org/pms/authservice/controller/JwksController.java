package org.pms.authservice.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public verification key so other services (the gateway) can check tokens. */
@RestController
public class JwksController {

  private final Map<String, Object> publicJwkSet;

  public JwksController(RSAKey signingJwk) {
    // toPublicJWK() drops d, p, q, dp, dq and qi; only the public view ever leaves the service.
    this.publicJwkSet = new JWKSet(signingJwk.toPublicJWK()).toJSONObject(true);
  }

  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return publicJwkSet;
  }
}
