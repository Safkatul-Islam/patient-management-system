package org.pms.patientservice.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

@Data
@JsonPropertyOrder({"id", "name", "email", "address", "dateOfBirth", "registeredDate"})
public class PatientResponseDto {
  private String id;
  private String name;
  private String email;
  private String address;
  private String dateOfBirth;
  private String registeredDate;
}
