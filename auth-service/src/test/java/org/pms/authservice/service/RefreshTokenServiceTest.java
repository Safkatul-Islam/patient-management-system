package org.pms.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.authservice.config.RefreshTokenProperties;
import org.pms.authservice.exception.InvalidRefreshTokenException;
import org.pms.authservice.model.RefreshToken;
import org.pms.authservice.model.RevocationReason;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.RefreshTokenRepository;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
  private static final Duration TTL = Duration.ofDays(7);
  private static final String RAW = "presented-token";

  @Mock private RefreshTokenRepository repository;

  private RefreshTokenService service;
  private User user;

  @BeforeEach
  void setUp() {
    service =
        new RefreshTokenService(
            repository, new RefreshTokenProperties(TTL), Clock.fixed(NOW, ZoneOffset.UTC));
    user = AccessTokenServiceTest.user(Role.NURSE, null);
  }

  @Test
  @DisplayName("Issued token is 32 random bytes, base64url without padding; only its hash is saved")
  void issueStoresHashOnly() {
    String raw = service.issue(user);

    assertThat(raw).doesNotContain("=", "+", "/");
    assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
    RefreshToken saved = captureSaved();
    assertThat(saved.getTokenHash()).isEqualTo(TokenHashing.sha256Hex(raw)).hasSize(64);
    assertThat(saved.getTokenHash()).isNotEqualTo(raw);
    assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(saved.getUser()).isSameAs(user);
  }

  @Test
  @DisplayName("Rotation revokes the presented token and stores a new one")
  void rotateRevokesAndReplaces() {
    RefreshToken current = token(NOW.plus(Duration.ofDays(1)));
    when(repository.findByTokenHash(TokenHashing.sha256Hex(RAW))).thenReturn(Optional.of(current));

    RefreshTokenService.Rotation rotation = service.rotate(RAW);

    assertThat(current.getRevokedAt()).isEqualTo(NOW);
    assertThat(current.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
    assertThat(rotation.user()).isSameAs(user);
    assertThat(rotation.refreshToken()).isNotEqualTo(RAW);
    RefreshToken replacement = captureSaved();
    assertThat(replacement.getTokenHash())
        .isEqualTo(TokenHashing.sha256Hex(rotation.refreshToken()));
    assertThat(replacement.isRevoked()).isFalse();
    assertThat(replacement.getRevocationReason()).isNull();
  }

  @Test
  @DisplayName("Presenting a ROTATED token revokes the user's active tokens as REUSE_DETECTED")
  void rotatedTokenReuseRevokesFamily() {
    RefreshToken current = revokedToken(RevocationReason.ROTATED);
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
    verify(repository).revokeAllActiveForUser(user.getId(), NOW, RevocationReason.REUSE_DETECTED);
    verify(repository, never()).save(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = RevocationReason.class,
      names = {"LOGOUT", "REUSE_DETECTED"})
  @DisplayName("Presenting a LOGOUT or REUSE_DETECTED token fails without family revocation")
  void nonRotatedRevokedTokenIsPlainRejection(RevocationReason reason) {
    RefreshToken current = revokedToken(reason);
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
    verify(repository, never()).revokeAllActiveForUser(any(), any(), any());
    verify(repository, never()).save(any());
    assertThat(current.getRevocationReason()).isEqualTo(reason);
  }

  @Test
  @DisplayName("A token expiring exactly now is expired")
  void expiredTokenRejected() {
    RefreshToken current = token(NOW);
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
    assertThat(current.isRevoked()).isFalse();
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("A token one second from expiry still rotates")
  void almostExpiredTokenRotates() {
    when(repository.findByTokenHash(anyString()))
        .thenReturn(Optional.of(token(NOW.plusSeconds(1))));

    assertThat(service.rotate(RAW).refreshToken()).isNotBlank();
  }

  @Test
  @DisplayName("An inactive user's token is rejected")
  void inactiveUserRejected() {
    ReflectionTestUtils.setField(user, "active", false);
    when(repository.findByTokenHash(anyString()))
        .thenReturn(Optional.of(token(NOW.plus(Duration.ofDays(1)))));

    assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("An unknown token is rejected")
  void unknownTokenRejected() {
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rotate(RAW)).isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  @DisplayName("Logout revokes a token owned by the caller")
  void revokeOwnToken() {
    RefreshToken current = token(NOW.plus(Duration.ofDays(1)));
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    service.revokeIfOwnedBy(RAW, user.getId());

    assertThat(current.getRevokedAt()).isEqualTo(NOW);
    assertThat(current.getRevocationReason()).isEqualTo(RevocationReason.LOGOUT);
  }

  @Test
  @DisplayName("Logout of an already rotated token keeps its original reason")
  void logoutKeepsEarlierReason() {
    RefreshToken current = revokedToken(RevocationReason.ROTATED);
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    service.revokeIfOwnedBy(RAW, user.getId());

    assertThat(current.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
    assertThat(current.getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
  }

  @Test
  @DisplayName("Logout leaves another user's token alone")
  void revokeForeignTokenIsNoOp() {
    RefreshToken current = token(NOW.plus(Duration.ofDays(1)));
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(current));

    service.revokeIfOwnedBy(RAW, UUID.randomUUID());

    assertThat(current.isRevoked()).isFalse();
  }

  private RefreshToken token(Instant expiresAt) {
    return new RefreshToken(user, TokenHashing.sha256Hex(RAW), expiresAt.minus(TTL), expiresAt);
  }

  private RefreshToken revokedToken(RevocationReason reason) {
    RefreshToken revoked = token(NOW.plus(Duration.ofDays(1)));
    revoked.revoke(NOW.minusSeconds(60), reason);
    return revoked;
  }

  private RefreshToken captureSaved() {
    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository).save(saved.capture());
    return saved.getValue();
  }
}
