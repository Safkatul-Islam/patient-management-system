package org.pms.authservice.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.pms.authservice.config.RefreshTokenProperties;
import org.pms.authservice.exception.InvalidRefreshTokenException;
import org.pms.authservice.model.RefreshToken;
import org.pms.authservice.model.RevocationReason;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque, single-use refresh tokens with reuse detection.
 *
 * <p>Every refresh revokes the presented token (reason {@code ROTATED}) and issues a new one in the
 * same transaction. If a {@code ROTATED} token is presented again, someone replayed it: either the
 * legitimate client or an attacker holds a stolen copy, and the server cannot tell which. All of
 * the user's active refresh tokens are then revoked (reason {@code REUSE_DETECTED}), forcing a
 * fresh login. A token revoked by logout or by an earlier reuse response is simply rejected: it is
 * not a theft signal, so the user's other sessions stay valid. Every rejection returns the same
 * 401, so the caller cannot tell which case applied.
 *
 * <p>Known consequence: a legitimate client that sends the same token twice concurrently (e.g. two
 * tabs refreshing at once) is logged out. The row lock makes the second request wait and then see
 * the first one's {@code ROTATED} revocation, which is indistinguishable from a replay.
 */
@Service
public class RefreshTokenService {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
  private static final int TOKEN_BYTES = 32;

  private final RefreshTokenRepository refreshTokenRepository;
  private final RefreshTokenProperties properties;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();

  public RefreshTokenService(
      RefreshTokenRepository refreshTokenRepository,
      RefreshTokenProperties properties,
      Clock clock) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.properties = properties;
    this.clock = clock;
  }

  /** The user a token was rotated for, and the replacement token (raw; only its hash is kept). */
  public record Rotation(User user, String refreshToken) {}

  /** Issues a new refresh token for the user and returns the raw value. */
  @Transactional
  public String issue(User user) {
    return store(user, clock.instant());
  }

  /**
   * Exchanges a refresh token for a new one. Reuse detection must commit its revocations even
   * though the request fails, hence no rollback for {@link InvalidRefreshTokenException}.
   */
  @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
  public Rotation rotate(String rawToken) {
    RefreshToken current =
        refreshTokenRepository
            .findByTokenHash(TokenHashing.sha256Hex(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);
    Instant now = clock.instant();
    User user = current.getUser();

    if (current.isRevoked()) {
      if (current.getRevocationReason() == RevocationReason.ROTATED) {
        int revoked =
            refreshTokenRepository.revokeAllActiveForUser(
                user.getId(), now, RevocationReason.REUSE_DETECTED);
        log.warn(
            "Rotated refresh token presented again; revoked {} active refresh token(s) of user {}",
            revoked,
            user.getId());
      }
      throw new InvalidRefreshTokenException();
    }
    if (current.isExpiredAt(now) || !user.isActive()) {
      throw new InvalidRefreshTokenException();
    }

    current.revoke(now, RevocationReason.ROTATED);
    return new Rotation(user, store(user, now));
  }

  /**
   * Revokes the token only if it belongs to {@code userId}. Unknown, foreign or already revoked
   * tokens are ignored silently so the caller learns nothing about other users' tokens.
   */
  @Transactional
  public void revokeIfOwnedBy(String rawToken, UUID userId) {
    refreshTokenRepository
        .findByTokenHash(TokenHashing.sha256Hex(rawToken))
        .filter(token -> token.getUser().getId().equals(userId))
        .ifPresent(token -> token.revoke(clock.instant(), RevocationReason.LOGOUT));
  }

  public long ttlSeconds() {
    return properties.ttl().toSeconds();
  }

  private String store(User user, Instant now) {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokenRepository.save(
        new RefreshToken(user, TokenHashing.sha256Hex(rawToken), now, now.plus(properties.ttl())));
    return rawToken;
  }
}
