package org.pms.patientservice.controller;

import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.model.Patient;
import org.pms.patientservice.service.PatientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
