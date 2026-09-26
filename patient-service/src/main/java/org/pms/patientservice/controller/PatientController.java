package org.pms.patientservice.controller;

import jakarta.validation.groups.Default;
import java.util.UUID;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.dto.validators.CreatePatientValidationGroup;
import org.pms.patientservice.security.GatewayIdentity;
import org.pms.patientservice.service.PatientService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {
  private final PatientService patientService;

  public PatientController(PatientService patientService) {
    this.patientService = patientService;
  }

  /**
   * Lists patients one page at a time ({@code ?page=&size=&sort=}); unsorted requests are ordered
   * by name. Page size defaults and limits come from {@code spring.data.web.pageable.*}.
   */
  @GetMapping
  public ResponseEntity<PagedModel<PatientResponseDto>> getPatients(
      @SortDefault(sort = "name") Pageable pageable) {
    return ResponseEntity.ok(new PagedModel<>(patientService.getPatients(pageable)));
  }

  /** A PATIENT may only read its own record; the service enforces that ownership rule. */
  @GetMapping("/{id}")
  public ResponseEntity<PatientResponseDto> getPatient(
      @PathVariable UUID id, @AuthenticationPrincipal GatewayIdentity caller) {
    return ResponseEntity.ok(patientService.getPatient(id, caller));
  }

  @PostMapping
  public ResponseEntity<PatientResponseDto> createPatient(
      @Validated({Default.class, CreatePatientValidationGroup.class}) @RequestBody
          PatientRequestDto patientRequestDto) {
    PatientResponseDto patientResponseDto = patientService.createPatient(patientRequestDto);
    return new ResponseEntity<>(patientResponseDto, HttpStatus.CREATED);
  }

  @PutMapping("/{id}")
  public ResponseEntity<PatientResponseDto> updatePatient(
      @PathVariable UUID id,
      @Validated({Default.class}) @RequestBody PatientRequestDto requestDto) {
    PatientResponseDto updatedPatient = patientService.updatePatient(id, requestDto);
    return ResponseEntity.ok(updatedPatient);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<HttpStatus> deletePatient(@PathVariable UUID id) {
    patientService.deletePatient(id);
    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }
}
