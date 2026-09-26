package org.pms.authservice.config;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Turns the configured private key into an RSA key pair. The public key is derived from the private
 * key's CRT parameters, so there is no separate public-key setting that could drift out of sync.
 *
 * <p>Error messages never include the configured value.
 */
final class SigningKeyLoader {

  static final int MIN_KEY_BITS = 2048;

  private SigningKeyLoader() {}

  record RsaKeyPair(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {}

  static RsaKeyPair load(String base64Pkcs8) {
    if (base64Pkcs8 == null || base64Pkcs8.isBlank()) {
      throw invalid("is not set", null);
    }
    byte[] der;
    try {
      der = Base64.getDecoder().decode(base64Pkcs8.strip());
    } catch (IllegalArgumentException ex) {
      throw invalid("is not valid single-line standard base64", null);
    }
    try {
      KeyFactory rsa = KeyFactory.getInstance("RSA");
      PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(der));
      if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
        throw invalid("does not carry RSA CRT parameters", null);
      }
      if (crtKey.getModulus().bitLength() < MIN_KEY_BITS) {
        throw invalid("is shorter than " + MIN_KEY_BITS + " bits", null);
      }
      BigInteger modulus = crtKey.getModulus();
      RSAPublicKey publicKey =
          (RSAPublicKey)
              rsa.generatePublic(new RSAPublicKeySpec(modulus, crtKey.getPublicExponent()));
      return new RsaKeyPair(publicKey, crtKey);
    } catch (GeneralSecurityException ex) {
      // The cause (a parser message about the DER structure) holds no key material.
      throw invalid("is not a DER-encoded PKCS#8 RSA private key", ex);
    }
  }

  private static IllegalStateException invalid(String reason, Throwable cause) {
    return new IllegalStateException(
        "pms.auth.jwt.private-key (AUTH_JWT_PRIVATE_KEY) " + reason, cause);
  }
}
