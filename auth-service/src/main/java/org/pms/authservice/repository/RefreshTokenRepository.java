package org.pms.authservice.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pms.authservice.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * Loads a token row with {@code SELECT ... FOR UPDATE}, so concurrent refreshes or logouts of the
   * same token serialize: the second caller blocks until the first commits, then sees the row's
   * committed state (already revoked).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** Revokes every still-active token of a user; returns how many rows changed. */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now"
          + " where t.user.id = :userId and t.revokedAt is null")
  int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
