package org.pms.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.authservice.dto.CreatePatientAccountRequest;
import org.pms.authservice.dto.CreateStaffUserRequest;
import org.pms.authservice.dto.UserResponse;
import org.pms.authservice.exception.EmailAlreadyInUseException;
import org.pms.authservice.exception.InvalidAccountRequestException;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
  private static final String PASSWORD = "correct-horse-battery";

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private UserAdminService service;

  @BeforeEach
  void setUp() {
    service =
        new UserAdminService(userRepository, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("Creates a staff user with a normalized email and a hashed password")
  void createsStaffUser() {
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
    when(userRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> withId(invocation.getArgument(0)));

    UserResponse response =
        service.createStaffUser(
            new CreateStaffUserRequest("  Nurse.Joy@PMS.Test ", PASSWORD, Role.NURSE, null));

    User saved = captureSaved();
    assertThat(saved.getEmail()).isEqualTo("nurse.joy@pms.test");
    assertThat(saved.getPasswordHash()).isEqualTo("bcrypt-hash");
    assertThat(saved.getRole()).isEqualTo(Role.NURSE);
    assertThat(saved.getPatientId()).isNull();
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    assertThat(response.id()).isNotNull();
    assertThat(response.email()).isEqualTo("nurse.joy@pms.test");
  }

  @Test
  @DisplayName("Creates a PATIENT account linked to the given patient id")
  void createsPatientAccount() {
    UUID patientId = UUID.randomUUID();
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
    when(userRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> withId(invocation.getArgument(0)));

    UserResponse response =
        service.createPatientAccount(
            new CreatePatientAccountRequest("pat@pms.test", PASSWORD, patientId));

    assertThat(response.role()).isEqualTo(Role.PATIENT);
    assertThat(response.patientId()).isEqualTo(patientId);
  }

  @Test
  @DisplayName("Staff endpoint refuses role PATIENT")
  void staffRejectsPatientRole() {
    assertInvalid(
        () ->
            service.createStaffUser(
                new CreateStaffUserRequest("a@pms.test", PASSWORD, Role.PATIENT, null)),
        "role");
  }

  @Test
  @DisplayName("Staff endpoint refuses a patient id")
  void staffRejectsPatientId() {
    assertInvalid(
        () ->
            service.createStaffUser(
                new CreateStaffUserRequest("a@pms.test", PASSWORD, Role.DOCTOR, UUID.randomUUID())),
        "patientId");
  }

  @Test
  @DisplayName("Patient account requires a patient id")
  void patientRequiresPatientId() {
    assertInvalid(
        () ->
            service.createPatientAccount(
                new CreatePatientAccountRequest("a@pms.test", PASSWORD, null)),
        "patientId");
  }

  @Test
  @DisplayName("Password of 72 characters but more than 72 UTF-8 bytes is rejected")
  void passwordOverByteLimit() {
    String seventyTwoCharsManyBytes = "é".repeat(72);

    assertInvalid(
        () ->
            service.createStaffUser(
                new CreateStaffUserRequest(
                    "a@pms.test", seventyTwoCharsManyBytes, Role.DOCTOR, null)),
        "password");
    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  @DisplayName("Password of exactly 72 ASCII bytes is accepted")
  void passwordAtByteLimit() {
    String password = "a".repeat(72);
    when(passwordEncoder.encode(password)).thenReturn("bcrypt-hash");
    when(userRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> withId(invocation.getArgument(0)));

    service.createStaffUser(new CreateStaffUserRequest("a@pms.test", password, Role.DOCTOR, null));

    verify(userRepository).saveAndFlush(any());
  }

  @Test
  @DisplayName("Password shorter than 12 characters is rejected by the service itself")
  void passwordTooShort() {
    assertInvalid(
        () ->
            service.createStaffUser(
                new CreateStaffUserRequest("a@pms.test", "short", Role.ADMIN, null)),
        "password");
  }

  @Test
  @DisplayName("Existing email (after normalization) is a conflict")
  void duplicateEmail() {
    when(userRepository.existsByEmail("taken@pms.test")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createStaffUser(
                    new CreateStaffUserRequest("TAKEN@pms.test", PASSWORD, Role.DOCTOR, null)))
        .isInstanceOf(EmailAlreadyInUseException.class);
    verify(userRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Unique-constraint race on insert is mapped to a conflict")
  void uniqueViolationRaceIsConflict() {
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
    when(userRepository.saveAndFlush(any()))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException(
                    "duplicate key", new SQLException(), "users_email_key")));

    assertThatThrownBy(
            () ->
                service.createStaffUser(
                    new CreateStaffUserRequest("race@pms.test", PASSWORD, Role.DOCTOR, null)))
        .isInstanceOf(EmailAlreadyInUseException.class);
  }

  @Test
  @DisplayName("Other integrity violations are not disguised as email conflicts")
  void otherViolationPropagates() {
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
    DataIntegrityViolationException other =
        new DataIntegrityViolationException(
            "check",
            new ConstraintViolationException("check", new SQLException(), "users_role_check"));
    when(userRepository.saveAndFlush(any())).thenThrow(other);

    assertThatThrownBy(
            () ->
                service.createStaffUser(
                    new CreateStaffUserRequest("x@pms.test", PASSWORD, Role.DOCTOR, null)))
        .isSameAs(other);
  }

  private static void assertInvalid(Runnable action, String field) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            InvalidAccountRequestException.class, ex -> assertThat(ex.getField()).isEqualTo(field));
  }

  private static User withId(User user) {
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    return user;
  }

  private User captureSaved() {
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(saved.capture());
    return saved.getValue();
  }
}
