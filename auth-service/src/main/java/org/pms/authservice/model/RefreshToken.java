package org.pms.authservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A stored refresh token. Holds only the SHA-256 hex digest, never the token itself. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  /** Set together with {@link #revokedAt}; the database enforces the pairing. */
  @Enumerated(EnumType.STRING)
  @Column(name = "revocation_reason", length = 20)
  private RevocationReason revocationReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public RefreshToken(User user, String tokenHash, Instant createdAt, Instant expiresAt) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now);
  }

  /** Revokes the token; a token that is already revoked keeps its original time and reason. */
  public void revoke(Instant now, RevocationReason reason) {
    if (revokedAt == null) {
      revokedAt = now;
      revocationReason = reason;
    }
  }
}
