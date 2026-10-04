package org.pms.apigateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * Ephemeral RSA keys generated in memory once per test JVM. No key material is written to disk or
 * committed.
 */
public final class TestKeys {

  public static final String PRIMARY_KID = "gw-test-key-1";
  public static final String ROTATED_KID = "gw-test-key-2";

  /** The key the stub JWKS publishes: stands in for auth-service's signing key. */
  public static final RSAKey PRIMARY = generate(PRIMARY_KID);

  /** A second key, published only by the key-rotation test. */
  public static final RSAKey ROTATED = generate(ROTATED_KID);

  /** Not published; reuses the primary's kid to impersonate it. */
  public static final RSAKey FOREIGN_SAME_KID = generate(PRIMARY_KID);

  /** An attacker's own key with its own kid. */
  public static final RSAKey ATTACKER = generate("attacker-key");

  private TestKeys() {}

  public static RSAKey generate(String kid) {
    try {
      return new RSAKeyGenerator(2048)
          .keyID(kid)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.RS256)
          .generate();
    } catch (JOSEException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
