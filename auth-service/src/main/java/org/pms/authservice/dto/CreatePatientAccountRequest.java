package org.pms.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Creates a PATIENT account linked to a patient record in patient-service. The id is trusted as
 * given by the ADMIN caller; it is not checked against patient-service.
 */
public record CreatePatientAccountRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotNull @Size(min = 12, max = 72) String password,
    @NotNull UUID patientId) {

  @Override
  public String toString() {
    return "CreatePatientAccountRequest[email=<redacted>, password=<redacted>]";
  }
}
