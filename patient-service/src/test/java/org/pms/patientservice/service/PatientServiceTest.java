package org.pms.patientservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import org.pms.patientservice.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

  private static final String EMAIL = "test.patient@example.com";

  @Mock private PatientRepository patientRepository;

  private PatientService patientService;

  @BeforeEach
  void setUp() {
    patientService = new PatientService(patientRepository, new PatientMapper());
  }

  @Test
  void createSavesThePatientAndReturnsIt() {
    UUID id = UUID.randomUUID();
    when(patientRepository.existsByEmail(EMAIL)).thenReturn(false);
    when(patientRepository.save(any(Patient.class)))
        .thenAnswer(
            invocation -> {
              Patient saved = invocation.getArgument(0);
              saved.setId(id);
              return saved;
            });

    PatientResponseDto created = patientService.createPatient(request(EMAIL, "2024-01-01"));

    assertThat(created.getId()).isEqualTo(id.toString());
    assertThat(created.getEmail()).isEqualTo(EMAIL);
    assertThat(created.getRegisteredDate()).isEqualTo("2024-01-01");
  }

  @Test
  void createWithTakenEmailThrowsConflictWithoutSaving() {
    when(patientRepository.existsByEmail(EMAIL)).thenReturn(true);

    assertThatThrownBy(() -> patientService.createPatient(request(EMAIL, "2024-01-01")))
        .isInstanceOf(EmailAlreadyExistsException.class)
        .message()
        .doesNotContain(EMAIL);
    verify(patientRepository, never()).save(any());
  }

  @Test
  void updateOfUnknownPatientThrowsNotFound() {
    UUID id = UUID.randomUUID();
    when(patientRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> patientService.updatePatient(id, request(EMAIL, null)))
        .isInstanceOf(PatientNotFoundException.class);
    verify(patientRepository, never()).save(any());
  }

  @Test
  void updateOntoAnotherPatientsEmailThrowsConflict() {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(patientRepository.existsByEmailAndIdNot(EMAIL, existing.getId())).thenReturn(true);

    assertThatThrownBy(() -> patientService.updatePatient(existing.getId(), request(EMAIL, null)))
        .isInstanceOf(EmailAlreadyExistsException.class);
    verify(patientRepository, never()).save(any());
  }

  @Test
  void updateWithoutRegisteredDateKeepsTheOriginal() {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(patientRepository.existsByEmailAndIdNot(EMAIL, existing.getId())).thenReturn(false);
    when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

    PatientResponseDto updated =
        patientService.updatePatient(existing.getId(), request(EMAIL, null));

    assertThat(updated.getRegisteredDate()).isEqualTo("2020-05-05");
    assertThat(updated.getEmail()).isEqualTo(EMAIL);
    assertThat(updated.getName()).isEqualTo("Updated Name");
    assertThat(updated.getDateOfBirth()).isEqualTo("1991-02-03");
  }

  @Test
  void updateWithRegisteredDateReplacesIt() {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(patientRepository.existsByEmailAndIdNot(EMAIL, existing.getId())).thenReturn(false);
    when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

    PatientResponseDto updated =
        patientService.updatePatient(existing.getId(), request(EMAIL, "2025-06-07"));

    assertThat(updated.getRegisteredDate()).isEqualTo("2025-06-07");
  }

  @Test
  void deleteOfUnknownPatientThrowsNotFound() {
    UUID id = UUID.randomUUID();
    when(patientRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> patientService.deletePatient(id))
        .isInstanceOf(PatientNotFoundException.class);
    verify(patientRepository, never()).delete(any());
  }

  @Test
  void deleteRemovesAnExistingPatient() {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

    patientService.deletePatient(existing.getId());

    verify(patientRepository).delete(existing);
  }

  @Test
  void listPassesPageAndSortThroughWithAnIdTiebreaker() {
    Pageable requested = PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "email"));
    Patient patient = existingPatient();
    when(patientRepository.findAll(any(Pageable.class)))
        .thenAnswer(inv -> new PageImpl<>(List.of(patient), inv.getArgument(0), 11));

    Page<PatientResponseDto> page = patientService.getPatients(requested);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(patientRepository).findAll(captor.capture());
    Pageable queried = captor.getValue();
    assertThat(queried.getPageNumber()).isEqualTo(2);
    assertThat(queried.getPageSize()).isEqualTo(5);
    assertThat(queried.getSort())
        .isEqualTo(Sort.by(Sort.Direction.DESC, "email").and(Sort.by("id")));

    assertThat(page.getTotalElements()).isEqualTo(11);
    assertThat(page.getContent())
        .singleElement()
        .satisfies(dto -> assertThat(dto.getId()).isEqualTo(patient.getId().toString()));
  }

  @Test
  void listRejectsASortPropertyOutsideTheAllowList() {
    Pageable requested = PageRequest.of(0, 20, Sort.by("address"));

    assertThatThrownBy(() -> patientService.getPatients(requested))
        .isInstanceOf(InvalidSortPropertyException.class);
    verifyNoInteractions(patientRepository);
  }

  @Test
  void patientReadingItsOwnRecordGetsIt() {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

    PatientResponseDto found =
        patientService.getPatient(existing.getId(), patient(existing.getId()));

    assertThat(found.getId()).isEqualTo(existing.getId().toString());
  }

  @Test
  void patientReadingAnotherRecordIsDeniedWithoutLookingItUp() {
    UUID otherId = UUID.randomUUID();

    assertThatThrownBy(() -> patientService.getPatient(otherId, patient(UUID.randomUUID())))
        .isInstanceOf(PatientAccessDeniedException.class)
        .message()
        .doesNotContain(otherId.toString());
    // No lookup: whether the other record exists must not influence the outcome.
    verifyNoInteractions(patientRepository);
  }

  @Test
  void patientReadingItsOwnMissingRecordGetsNotFound() {
    UUID ownId = UUID.randomUUID();
    when(patientRepository.findById(ownId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> patientService.getPatient(ownId, patient(ownId)))
        .isInstanceOf(PatientNotFoundException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"ADMIN", "DOCTOR", "NURSE", "BILLING_STAFF"})
  void staffReadAnyRecord(Role role) {
    Patient existing = existingPatient();
    when(patientRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

    PatientResponseDto found =
        patientService.getPatient(
            existing.getId(), new GatewayIdentity(UUID.randomUUID(), role, null));

    assertThat(found.getId()).isEqualTo(existing.getId().toString());
  }

  @Test
  void staffReadingAMissingRecordGetsNotFound() {
    UUID id = UUID.randomUUID();
    when(patientRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                patientService.getPatient(
                    id, new GatewayIdentity(UUID.randomUUID(), Role.DOCTOR, null)))
        .isInstanceOf(PatientNotFoundException.class);
  }

  private static GatewayIdentity patient(UUID ownPatientId) {
    return new GatewayIdentity(UUID.randomUUID(), Role.PATIENT, ownPatientId);
  }

  private static Patient existingPatient() {
    return new Patient(
        UUID.randomUUID(),
        "Original Name",
        "original@example.com",
        "1 Original St",
        LocalDate.of(1980, 1, 1),
        LocalDate.of(2020, 5, 5));
  }

  private static PatientRequestDto request(String email, String registeredDate) {
    PatientRequestDto request = new PatientRequestDto();
    request.setName("Updated Name");
    request.setEmail(email);
    request.setAddress("2 Updated St");
    request.setDateOfBirth("1991-02-03");
    request.setRegisteredDate(registeredDate);
    return request;
  }
}
