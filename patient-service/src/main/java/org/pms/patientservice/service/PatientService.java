package org.pms.patientservice.service;

import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.exception.EmailAlreadyExistsException;
import org.pms.patientservice.exception.PatientNotFoundException;
import org.pms.patientservice.mapper.PatientMapper;
import org.pms.patientservice.model.Patient;
import org.pms.patientservice.repository.PatientRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;

    public PatientService(PatientRepository patientRepository,
                          PatientMapper patientMapper) {
        this.patientRepository = patientRepository;
        this.patientMapper = patientMapper;
    }

    public List<PatientResponseDto> getAllPatient() {
        List<Patient> patients = patientRepository.findAll();

        return patients.stream()
                .map(patientMapper::mapToDto)
                .toList();
    }

    public PatientResponseDto createPatient(PatientRequestDto patientRequestDto) {
        if (patientRepository.existsByEmail(patientRequestDto.getEmail())) {
            throw new EmailAlreadyExistsException("A patient with email " + patientRequestDto.getEmail() + " already exists!");
        }
        Patient newPatient = patientRepository.save(patientMapper.mapToEntity(patientRequestDto));

        return patientMapper.mapToDto(newPatient);
    }

    public PatientResponseDto updatePatient(UUID id, PatientRequestDto patientRequestDto) {
        Patient patient = patientRepository.findById(id).orElseThrow(() -> new PatientNotFoundException("Patient not found with ID: " + id));

        if (patientRepository.existsByEmailAndIdNot(patientRequestDto.getEmail(), id)) {
            throw new EmailAlreadyExistsException("A patient with email " + patientRequestDto.getEmail() + " already exists!");
        }

        patient.setName(patientRequestDto.getName());
        patient.setEmail(patientRequestDto.getEmail());
        patient.setAddress(patientRequestDto.getAddress());
        patient.setDateOfBirth(LocalDate.parse(patientRequestDto.getDateOfBirth()));
        patient.setRegisteredDate(LocalDate.parse(patientRequestDto.getRegisteredDate()));

        Patient updatedPatient = patientRepository.save(patient);

        return patientMapper.mapToDto(updatedPatient);

    }
}