package org.pms.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code /auth/refresh} and {@code /auth/logout}. */
public record RefreshTokenRequest(@NotBlank @Size(max = 256) String refreshToken) {

  @Override
  public String toString() {
    return "RefreshTokenRequest[refreshToken=<redacted>]";
  }
}
