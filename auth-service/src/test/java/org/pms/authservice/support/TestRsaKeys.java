package org.pms.authservice.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Ephemeral RSA keys generated in memory once per test JVM. No key material is ever written to disk
 * or committed.
 */
public final class TestRsaKeys {

  public static final String KEY_ID = "test-signing-key";

  /** The service's signing key, supplied through {@code pms.auth.jwt.private-key}. */
  public static final KeyPair SIGNING = generate(2048);

  private TestRsaKeys() {}

  /** Base64 of the DER PKCS#8 encoding: the format AUTH_JWT_PRIVATE_KEY expects. */
  public static String signingPrivateKeyBase64() {
    return base64Pkcs8(SIGNING);
  }

  public static RSAPublicKey signingPublicKey() {
    return (RSAPublicKey) SIGNING.getPublic();
  }

  public static RSAPrivateKey signingPrivateKey() {
    return (RSAPrivateKey) SIGNING.getPrivate();
  }

  public static String base64Pkcs8(KeyPair keyPair) {
    return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }

  public static KeyPair generate(int bits) {
    return generate("RSA", bits);
  }

  public static KeyPair generate(String algorithm, int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
