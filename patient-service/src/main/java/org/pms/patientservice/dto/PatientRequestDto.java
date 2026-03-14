package org.pms.patientservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatientRequestDto {
    @NotBlank
    @Size(max = 100, message = "Name cannot exceed 100 characters!")
    private String name;

    @NotBlank(message = "Email is required!")
    @Email(message = "Invalid email format!")
    private String email;

    @NotBlank(message = "Address is required!")
    private String address;

    @NotNull(message = "Date of Birth is required!")
    private String dateOfBirth;

    @NotNull(message = "Register date is required!")
    private String registeredDate;
}
