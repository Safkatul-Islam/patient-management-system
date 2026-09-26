package org.pms.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No format rules beyond presence and a sane bound: a wrong value is just a failed login. */
public record LoginRequest(
    @NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 256) String password) {

  @Override
  public String toString() {
    return "LoginRequest[email=<redacted>, password=<redacted>]";
  }
}
