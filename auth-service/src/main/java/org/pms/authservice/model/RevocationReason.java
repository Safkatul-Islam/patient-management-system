package org.pms.authservice.model;

/** Why a refresh token stopped working. Only {@link #ROTATED} makes a replay a theft signal. */
public enum RevocationReason {
  /** Exchanged for a new token by a refresh; presenting it again is reuse. */
  ROTATED,
  /** Revoked by its owner through logout. */
  LOGOUT,
  /** Revoked because another token of the same user was reused. */
  REUSE_DETECTED
}
