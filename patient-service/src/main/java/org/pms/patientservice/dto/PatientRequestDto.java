package org.pms.patientservice.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

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

    @JsonFormat(pattern = "yyyy-MM-DD")
    @NotNull(message = "Date of Birth is required!")
    private LocalDate dateOfBirth;

    @JsonFormat(pattern = "yyyy-MM-DD")
    @NotNull(message = "Register date is required!")
    private LocalDate registeredDate;
}
