package org.pms.patientservice.dto;

import lombok.Data;

@Data
public class PatientResponseDto {
    private String name;
    private String email;
    private String dateOfBirth;
}
