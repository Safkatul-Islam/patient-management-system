package org.pms.apigateway.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * The caller's identity, taken only from a token whose signature and standard claims have already
 * been verified, and checked against the identity header contract before anything is forwarded.
 *
 * @param userId the {@code sub} claim
 * @param role the single role in the {@code roles} claim (V1 contract: exactly one)
 * @param patientId the {@code patientId} claim; non-null if and only if {@code role} is PATIENT
 */
public record VerifiedIdentity(UUID userId, Role role, UUID patientId) {

  public static final String ROLES_CLAIM = "roles";
  public static final String PATIENT_ID_CLAIM = "patientId";

  // Deliberately generic: the reason a token was rejected is never disclosed or logged.
  private static final String INVALID = "Invalid token";

  /**
   * Extracts and validates the identity claims.
   *
   * @throws InvalidBearerTokenException if any identity claim is missing or malformed, so the
   *     request ends as a normal 401 and is never forwarded
   */
  public static VerifiedIdentity from(Jwt jwt) {
    Map<String, Object> claims = jwt.getClaims();
    UUID userId = canonicalUuid(jwt.getSubject());
    Role role = singleRole(claims.get(ROLES_CLAIM));
    UUID patientId =
        claims.containsKey(PATIENT_ID_CLAIM) ? canonicalUuid(claims.get(PATIENT_ID_CLAIM)) : null;
    if ((role == Role.PATIENT) != (patientId != null)) {
      throw invalid();
    }
    return new VerifiedIdentity(userId, role, patientId);
  }

  /** The {@code X-User-Roles} value: the single role name. */
  public String rolesHeaderValue() {
    return role.name();
  }

  /** Only the lowercase canonical form is accepted, so the forwarded value is unambiguous. */
  private static UUID canonicalUuid(Object value) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    try {
      UUID uuid = UUID.fromString(text);
      if (!uuid.toString().equals(text)) {
        throw invalid();
      }
      return uuid;
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
  }

  /**
   * auth-service issues exactly one role, as a one-element JSON array. Anything else (empty, two or
   * more, duplicates, unknown or non-string values, a bare string) is rejected rather than
   * forwarded as a multi-role header downstream services were never designed for.
   */
  private static Role singleRole(Object claim) {
    if (!(claim instanceof List<?> values) || values.size() != 1) {
      throw invalid();
    }
    if (values.get(0) instanceof String name) {
      for (Role role : Role.values()) {
        if (role.name().equals(name)) {
          return role;
        }
      }
    }
    throw invalid();
  }

  private static InvalidBearerTokenException invalid() {
    return new InvalidBearerTokenException(INVALID);
  }
}
