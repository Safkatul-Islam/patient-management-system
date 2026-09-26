package org.pms.authservice.service;

import java.time.Clock;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.pms.authservice.dto.CreatePatientAccountRequest;
import org.pms.authservice.dto.CreateStaffUserRequest;
import org.pms.authservice.dto.UserResponse;
import org.pms.authservice.exception.EmailAlreadyInUseException;
import org.pms.authservice.exception.InvalidAccountRequestException;
import org.pms.authservice.exception.PatientAlreadyLinkedException;
import org.pms.authservice.mapper.UserMapper;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account creation. There is no self-registration: every account is created by an ADMIN. */
@Service
public class UserAdminService {

  static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";
  static final String PATIENT_UNIQUE_CONSTRAINT = "users_patient_id_key";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public UserAdminService(
      UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  @Transactional
  public UserResponse createStaffUser(CreateStaffUserRequest request) {
    if (request.role() == null || !request.role().isStaff()) {
      throw new InvalidAccountRequestException(
          "role", "must be one of ADMIN, DOCTOR, NURSE, BILLING_STAFF");
    }
    if (request.patientId() != null) {
      throw new InvalidAccountRequestException("patientId", "must not be set for staff accounts");
    }
    return UserMapper.toResponse(create(request.email(), request.password(), request.role(), null));
  }

  /**
   * The patient id is trusted as given; patient-service is not consulted. A patient has at most one
   * login account.
   */
  @Transactional
  public UserResponse createPatientAccount(CreatePatientAccountRequest request) {
    if (request.patientId() == null) {
      throw new InvalidAccountRequestException("patientId", "must not be null");
    }
    return UserMapper.toResponse(
        create(request.email(), request.password(), Role.PATIENT, request.patientId()));
  }

  private User create(String email, String password, Role role, UUID patientId) {
    Credentials.requireValidPassword(password);
    String normalizedEmail = Credentials.normalizeEmail(email);
    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new EmailAlreadyInUseException();
    }
    if (patientId != null && userRepository.existsByPatientId(patientId)) {
      throw new PatientAlreadyLinkedException();
    }
    User user =
        new User(
            normalizedEmail, passwordEncoder.encode(password), role, patientId, clock.instant());
    try {
      return userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
      // A concurrent request took the email or the patient between the checks and the insert.
      String constraint = violatedConstraint(ex);
      if (EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
        throw new EmailAlreadyInUseException();
      }
      if (PATIENT_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
        throw new PatientAlreadyLinkedException();
      }
      throw ex;
    }
  }

  private static String violatedConstraint(DataIntegrityViolationException ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }
}
