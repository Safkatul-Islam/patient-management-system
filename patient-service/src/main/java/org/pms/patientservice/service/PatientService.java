package org.pms.patientservice.service;

import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.mapper.PatientResponseMapper;
import org.pms.patientservice.model.Patient;
import org.pms.patientservice.repository.PatientRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PatientService {

    private final PatientRepository patientRepository;

    private final PatientResponseMapper patientMapper;

    public PatientService(PatientRepository patientRepository,
                          PatientResponseMapper patientMapper) {
        this.patientRepository = patientRepository;
        this.patientMapper = patientMapper;
    }

    public List<PatientResponseDto> getAllPatient() {
        List<Patient> patients = patientRepository.findAll();

        return patients.stream()
                .map(patientMapper::mapToDto)
                .toList();
    }
}
