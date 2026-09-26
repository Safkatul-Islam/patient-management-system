package org.pms.authservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Refresh tokens are 256 bits of randomness, so a plain SHA-256 (no salt, no work factor) is enough
 * to make a leaked table useless while keeping lookup by hash possible.
 */
public final class TokenHashing {

  private TokenHashing() {}

  public static String sha256Hex(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
