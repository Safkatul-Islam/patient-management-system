package org.pms.authservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.authservice.dto.CreateStaffUserRequest;
import org.pms.authservice.model.Role;
import org.pms.authservice.repository.UserRepository;
import org.pms.authservice.service.UserAdminService;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminRunnerTest {

  private static final String EMAIL = "first-admin@pms.test";
  private static final String PASSWORD = "bootstrap-password";
  private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

  @Mock private UserRepository userRepository;
  @Mock private UserAdminService userAdminService;

  private final Validator validator = VALIDATION.getValidator();

  @AfterAll
  static void closeValidation() {
    VALIDATION.close();
  }

  @Test
  @DisplayName("Creates the ADMIN when both variables are set and no ADMIN exists")
  void createsAdmin() {
    when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

    runner(EMAIL, PASSWORD).run(new DefaultApplicationArguments());

    ArgumentCaptor<CreateStaffUserRequest> request =
        ArgumentCaptor.forClass(CreateStaffUserRequest.class);
    verify(userAdminService).createStaffUser(request.capture());
    assertThat(request.getValue().email()).isEqualTo(EMAIL);
    assertThat(request.getValue().role()).isEqualTo(Role.ADMIN);
    assertThat(request.getValue().patientId()).isNull();
  }

  @Test
  @DisplayName("Does nothing when an ADMIN already exists")
  void skipsWhenAdminExists() {
    when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

    runner(EMAIL, PASSWORD).run(new DefaultApplicationArguments());

    verifyNoInteractions(userAdminService);
  }

  @Test
  @DisplayName("Does nothing when the variables are absent")
  void skipsWhenNotConfigured() {
    runner("", "").run(new DefaultApplicationArguments());
    runner(null, null).run(new DefaultApplicationArguments());

    verifyNoInteractions(userRepository, userAdminService);
  }

  @Test
  @DisplayName("Does nothing when only one variable is set")
  void skipsWhenPartiallyConfigured() {
    runner(EMAIL, "").run(new DefaultApplicationArguments());
    runner("", PASSWORD).run(new DefaultApplicationArguments());

    verifyNoInteractions(userRepository, userAdminService);
  }

  @Test
  @DisplayName("Invalid configured email fails startup without echoing the value")
  void invalidEmailFailsWithoutEchoingIt() {
    when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

    assertThatThrownBy(
            () -> runner("not-an-email", PASSWORD).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("email")
        .hasMessageNotContaining("not-an-email");
    verifyNoInteractions(userAdminService);
  }

  private BootstrapAdminRunner runner(String email, String password) {
    return new BootstrapAdminRunner(
        new BootstrapAdminProperties(email, password), userRepository, userAdminService, validator);
  }
}
