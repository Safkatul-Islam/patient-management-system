package org.pms.authservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.authservice.exception.InvalidCredentialsException;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Login must spend one BCrypt comparison on every path, and never issue tokens on failure. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final String DUMMY_HASH = "dummy-hash";

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AccessTokenService accessTokenService;
  @Mock private RefreshTokenService refreshTokenService;

  private AuthService service;

  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
    service =
        new AuthService(userRepository, passwordEncoder, accessTokenService, refreshTokenService);
  }

  @Test
  @DisplayName("Unknown email still runs one BCrypt comparison against the dummy hash")
  void unknownEmailComparesDummyHash() {
    when(userRepository.findByEmail("ghost@pms.test")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.login(" Ghost@PMS.test ", "whatever-password"))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(passwordEncoder, times(1)).matches("whatever-password", DUMMY_HASH);
    verify(refreshTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("Inactive user with the right password is rejected after the comparison")
  void inactiveUserRejected() {
    User user = AccessTokenServiceTest.user(Role.DOCTOR, null);
    ReflectionTestUtils.setField(user, "active", false);
    when(userRepository.findByEmail("someone@pms.test")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("right-password", "hash")).thenReturn(true);

    assertThatThrownBy(() -> service.login("someone@pms.test", "right-password"))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(refreshTokenService, never()).issue(any());
  }

  @Test
  @DisplayName("Password over 72 bytes never reaches BCrypt but still costs one comparison")
  void oversizedPasswordStillComparesOnce() {
    User user = AccessTokenServiceTest.user(Role.DOCTOR, null);
    when(userRepository.findByEmail("someone@pms.test")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.login("someone@pms.test", "x".repeat(73)))
        .isInstanceOf(InvalidCredentialsException.class);
    verify(passwordEncoder, times(1)).matches(anyString(), eq("hash"));
    verify(passwordEncoder, never()).matches(eq("x".repeat(73)), anyString());
  }
}
