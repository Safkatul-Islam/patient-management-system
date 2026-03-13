package org.pms.patientservice.mapper;

import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.model.Patient;
import org.springframework.stereotype.Component;

@Component
public class PatientResponseMapper {

    public PatientResponseDto mapToDto(Patient patient) {
        PatientResponseDto responseDto = new PatientResponseDto();
        responseDto.setName(patient.getName());
        responseDto.setEmail(patient.getEmail());
        responseDto.setDateOfBirth(patient.getDateOfBirth().toString());

        return responseDto;
    }
}
