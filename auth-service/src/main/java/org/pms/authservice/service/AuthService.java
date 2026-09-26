package org.pms.authservice.service;

import java.util.UUID;
import org.pms.authservice.dto.TokenResponse;
import org.pms.authservice.exception.InvalidCredentialsException;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, refresh and logout. Not transactional itself: each step's transaction lives in {@link
 * RefreshTokenService}, so a failed refresh can still commit its reuse-detection revocations.
 */
@Service
public class AuthService {

  /** Compared against the dummy hash when the real password cannot be (over BCrypt's limit). */
  private static final String TIMING_ONLY_INPUT = "timing-only";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokenService accessTokenService;
  private final RefreshTokenService refreshTokenService;

  /** Hash of a random value nobody knows; checked when the email is unknown. */
  private final String dummyPasswordHash;

  public AuthService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      AccessTokenService accessTokenService,
      RefreshTokenService refreshTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.accessTokenService = accessTokenService;
    this.refreshTokenService = refreshTokenService;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Unknown email, wrong password and inactive account all fail the same way. Exactly one BCrypt
   * comparison runs in every case, so response time does not reveal whether an account exists.
   */
  public TokenResponse login(String email, String password) {
    User user = userRepository.findByEmail(Credentials.normalizeEmail(email)).orElse(null);
    boolean fits = Credentials.fitsBcrypt(password);
    String hash = user != null ? user.getPasswordHash() : dummyPasswordHash;
    boolean passwordMatches = passwordEncoder.matches(fits ? password : TIMING_ONLY_INPUT, hash);
    if (user == null || !fits || !passwordMatches || !user.isActive()) {
      throw new InvalidCredentialsException();
    }
    return tokens(user, refreshTokenService.issue(user));
  }

  public TokenResponse refresh(String refreshToken) {
    RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
    return tokens(rotation.user(), rotation.refreshToken());
  }

  public void logout(UUID userId, String refreshToken) {
    refreshTokenService.revokeIfOwnedBy(refreshToken, userId);
  }

  private TokenResponse tokens(User user, String refreshToken) {
    return new TokenResponse(
        accessTokenService.issue(user),
        TokenResponse.BEARER,
        accessTokenService.ttlSeconds(),
        refreshToken,
        refreshTokenService.ttlSeconds());
  }
}
