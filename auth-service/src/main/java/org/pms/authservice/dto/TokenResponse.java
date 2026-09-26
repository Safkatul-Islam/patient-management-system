package org.pms.authservice.dto;

/**
 * Token pair returned by login and refresh. Lifetimes are in seconds.
 *
 * @param tokenType always {@code Bearer}
 */
public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    long refreshExpiresIn) {

  public static final String BEARER = "Bearer";

  @Override
  public String toString() {
    return "TokenResponse[tokenType=%s, expiresIn=%d, refreshExpiresIn=%d]"
        .formatted(tokenType, expiresIn, refreshExpiresIn);
  }
}
