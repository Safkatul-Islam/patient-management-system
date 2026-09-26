package org.pms.authservice.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.pms.authservice.dto.CreateStaffUserRequest;
import org.pms.authservice.model.Role;
import org.pms.authservice.repository.UserRepository;
import org.pms.authservice.service.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN at startup, since admin endpoints are the only way to create accounts.
 * Runs only when both bootstrap variables are set and no ADMIN exists yet, so it is a no-op on
 * every later start. Invalid configured credentials fail startup. Neither logs nor error messages
 * ever include the configured email or password.
 */
@Component
class BootstrapAdminRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final BootstrapAdminProperties properties;
  private final UserRepository userRepository;
  private final UserAdminService userAdminService;
  private final Validator validator;

  BootstrapAdminRunner(
      BootstrapAdminProperties properties,
      UserRepository userRepository,
      UserAdminService userAdminService,
      Validator validator) {
    this.properties = properties;
    this.userRepository = userRepository;
    this.userAdminService = userAdminService;
    this.validator = validator;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.isConfigured()) {
      if (properties.isPartiallyConfigured()) {
        log.warn(
            "Bootstrap admin skipped: set both AUTH_BOOTSTRAP_ADMIN_EMAIL and"
                + " AUTH_BOOTSTRAP_ADMIN_PASSWORD");
      } else {
        log.info("Bootstrap admin skipped: not configured");
      }
      return;
    }
    if (userRepository.existsByRole(Role.ADMIN)) {
      log.info("Bootstrap admin skipped: an ADMIN account already exists");
      return;
    }
    CreateStaffUserRequest request =
        new CreateStaffUserRequest(properties.email(), properties.password(), Role.ADMIN, null);
    requireValid(request);
    userAdminService.createStaffUser(request);
    log.info("Bootstrap admin account created");
  }

  /** Same rules as the HTTP endpoint; reports only which fields are invalid, never values. */
  private void requireValid(CreateStaffUserRequest request) {
    Set<ConstraintViolation<CreateStaffUserRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      String fields =
          violations.stream()
              .map(violation -> violation.getPropertyPath().toString())
              .sorted()
              .collect(Collectors.joining(", "));
      throw new IllegalStateException("Invalid bootstrap admin configuration: " + fields);
    }
  }
}
