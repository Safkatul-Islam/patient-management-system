package org.pms.patientservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.pms.patientservice.dto.validators.CreatePatientValidationGroup;

@Data
public class PatientRequestDto {
  // Each length limit fits the VARCHAR(255) column, so an over-long value is a 400, not a 500.
  @NotBlank
  @Size(max = 100, message = "Name cannot exceed 100 characters!")
  private String name;

  @NotBlank(message = "Email is required!")
  @Email(message = "Invalid email format!")
  @Size(max = 255, message = "Email cannot exceed 255 characters!")
  private String email;

  @NotBlank(message = "Address is required!")
  @Size(max = 255, message = "Address cannot exceed 255 characters!")
  private String address;

  @NotBlank(message = "Date of Birth is required!")
  private String dateOfBirth;

  @NotBlank(groups = CreatePatientValidationGroup.class, message = "Register date is required!")
  private String registeredDate;
}
