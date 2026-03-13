package org.pms.patientservice.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@Data
@JsonPropertyOrder({ "name", "email", "dateOfBirth" })
public class PatientResponseDto {
    private String name;
    private String email;
    private String dateOfBirth;
}
