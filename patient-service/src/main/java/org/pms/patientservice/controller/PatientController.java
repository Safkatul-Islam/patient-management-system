package org.pms.patientservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.dto.validators.CreatePatientValidationGroup;
import org.pms.patientservice.service.PatientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PatientController {
    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @GetMapping("/patients")
    public ResponseEntity<List<PatientResponseDto>> getAllPatient() {
        List<PatientResponseDto> patients = patientService.getAllPatient();
        return ResponseEntity.ok(patients);
    }

    @PostMapping("/patient")
    public ResponseEntity<PatientResponseDto> createPatient(@Validated({ Default.class, CreatePatientValidationGroup.class }) @RequestBody PatientRequestDto patientRequestDto) {
        PatientResponseDto patientResponseDto = patientService.createPatient(patientRequestDto);
        return new ResponseEntity<>(patientResponseDto, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PatientResponseDto> updatePatient(@PathVariable UUID id, @Validated({ Default.class }) @RequestBody PatientRequestDto requestDto) {
        PatientResponseDto updatedPatient = patientService.updatePatient(id, requestDto);
        return ResponseEntity.ok(updatedPatient);
    }
}
