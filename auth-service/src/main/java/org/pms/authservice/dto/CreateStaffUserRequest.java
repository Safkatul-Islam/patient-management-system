package org.pms.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.pms.authservice.model.Role;

/**
 * Creates a staff account. {@code patientId} is accepted only so that a caller sending one gets a
 * clear 400 instead of having it silently ignored.
 */
public record CreateStaffUserRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotNull @Size(min = 12, max = 72) String password,
    @NotNull Role role,
    @Null(message = "must not be set for staff accounts") UUID patientId) {

  @Override
  public String toString() {
    return "CreateStaffUserRequest[email=<redacted>, password=<redacted>, role=%s]".formatted(role);
  }
}
