package org.pms.patientservice.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.dto.PatientResponseDto;
import org.pms.patientservice.exception.EmailAlreadyExistsException;
import org.pms.patientservice.exception.InvalidSortPropertyException;
import org.pms.patientservice.exception.PatientAccessDeniedException;
import org.pms.patientservice.exception.PatientNotFoundException;
import org.pms.patientservice.mapper.PatientMapper;
import org.pms.patientservice.model.Patient;
import org.pms.patientservice.repository.PatientRepository;
import org.pms.patientservice.security.GatewayIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class PatientService {

  /** Properties clients may sort the patient list by; anything else is rejected. */
  static final List<String> SORTABLE_PROPERTIES =
      List.of("name", "email", "dateOfBirth", "registeredDate");

  /** Appended to every sort so rows with equal sort keys keep a stable order across pages. */
  private static final Sort TIEBREAKER = Sort.by("id");

  private final PatientRepository patientRepository;
  private final PatientMapper patientMapper;

  public PatientService(PatientRepository patientRepository, PatientMapper patientMapper) {
    this.patientRepository = patientRepository;
    this.patientMapper = patientMapper;
  }

  public Page<PatientResponseDto> getPatients(Pageable pageable) {
    for (Sort.Order order : pageable.getSort()) {
      if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
        throw new InvalidSortPropertyException(SORTABLE_PROPERTIES);
      }
    }
    Pageable stablePageable =
        PageRequest.of(
            pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(TIEBREAKER));

    return patientRepository.findAll(stablePageable).map(patientMapper::mapToDto);
  }

  /**
   * Staff may read any record; a PATIENT only its own. Ownership is checked before the lookup, so a
   * PATIENT asking for another id is refused whether or not that record exists: the answer never
   * tells it which ids are in use.
   */
  public PatientResponseDto getPatient(UUID id, GatewayIdentity caller) {
    Objects.requireNonNull(caller, "caller is required");
    if (caller.isPatient() && !id.equals(caller.patientId())) {
      throw new PatientAccessDeniedException();
    }
    return patientRepository
        .findById(id)
        .map(patientMapper::mapToDto)
        .orElseThrow(() -> new PatientNotFoundException("Patient not found with ID: " + id));
  }

  public PatientResponseDto createPatient(PatientRequestDto patientRequestDto) {
    if (patientRepository.existsByEmail(patientRequestDto.getEmail())) {
      throw new EmailAlreadyExistsException();
    }
    Patient newPatient = patientRepository.save(patientMapper.mapToEntity(patientRequestDto));

    return patientMapper.mapToDto(newPatient);
  }

  public PatientResponseDto updatePatient(UUID id, PatientRequestDto patientRequestDto) {
    Patient patient =
        patientRepository
            .findById(id)
            .orElseThrow(() -> new PatientNotFoundException("Patient not found with ID: " + id));

    if (patientRepository.existsByEmailAndIdNot(patientRequestDto.getEmail(), id)) {
      throw new EmailAlreadyExistsException();
    }

    patient.setName(patientRequestDto.getName());
    patient.setEmail(patientRequestDto.getEmail());
    patient.setAddress(patientRequestDto.getAddress());
    patient.setDateOfBirth(LocalDate.parse(patientRequestDto.getDateOfBirth()));

    // registeredDate is only required when creating a patient, so an update
    // that omits it keeps the date the patient was originally registered on.
    if (hasText(patientRequestDto.getRegisteredDate())) {
      patient.setRegisteredDate(LocalDate.parse(patientRequestDto.getRegisteredDate()));
    }

    Patient updatedPatient = patientRepository.save(patient);

    return patientMapper.mapToDto(updatedPatient);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public void deletePatient(UUID id) {
    Patient patient =
        patientRepository
            .findById(id)
            .orElseThrow(() -> new PatientNotFoundException("Patient not found with ID: " + id));

    patientRepository.delete(patient);
  }
}
