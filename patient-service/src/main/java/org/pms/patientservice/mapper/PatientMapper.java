package org.pms.patientservice.mapper;

import java.time.LocalDate;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.model.Patient;
import org.springframework.stereotype.Component;

@Component
public class PatientMapper {

  public PatientResponseDto mapToDto(Patient patient) {
    PatientResponseDto responseDto = new PatientResponseDto();
    responseDto.setId(patient.getId().toString());
    responseDto.setName(patient.getName());
    responseDto.setEmail(patient.getEmail());
    responseDto.setAddress(patient.getAddress());
    responseDto.setDateOfBirth(patient.getDateOfBirth().toString());
    responseDto.setRegisteredDate(patient.getRegisteredDate().toString());

    return responseDto;
  }

  public Patient mapToEntity(PatientRequestDto patientRequestDto) {
    Patient patient = new Patient();
    patient.setName(patientRequestDto.getName());
    patient.setEmail(patientRequestDto.getEmail());
    patient.setAddress(patientRequestDto.getAddress());
    patient.setDateOfBirth(LocalDate.parse(patientRequestDto.getDateOfBirth()));
    patient.setRegisteredDate(LocalDate.parse(patientRequestDto.getRegisteredDate()));

    return patient;
  }
}
