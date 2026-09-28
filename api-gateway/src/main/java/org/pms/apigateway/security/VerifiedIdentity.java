package org.pms.apigateway.security;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * The caller's identity, taken only from a token whose signature and standard claims have already
 * been verified, and checked against the identity header contract before anything is forwarded.
 *
 * @param userId the {@code sub} claim
 * @param roles the {@code roles} claim, in token order
 * @param patientId the {@code patientId} claim; non-null if and only if {@code roles} contains
 *     PATIENT
 */
public record VerifiedIdentity(UUID userId, List<Role> roles, UUID patientId) {

  public static final String ROLES_CLAIM = "roles";
  public static final String PATIENT_ID_CLAIM = "patientId";

  // Deliberately generic: the reason a token was rejected is never disclosed or logged.
  private static final String INVALID = "Invalid token";

  public VerifiedIdentity {
    roles = List.copyOf(roles);
  }

  /**
   * Extracts and validates the identity claims.
   *
   * @throws InvalidBearerTokenException if any identity claim is missing or malformed, so the
   *     request ends as a normal 401 and is never forwarded
   */
  public static VerifiedIdentity from(Jwt jwt) {
    Map<String, Object> claims = jwt.getClaims();
    UUID userId = canonicalUuid(jwt.getSubject());
    List<Role> roles = roles(claims.get(ROLES_CLAIM));
    UUID patientId =
        claims.containsKey(PATIENT_ID_CLAIM) ? canonicalUuid(claims.get(PATIENT_ID_CLAIM)) : null;
    boolean patient = roles.contains(Role.PATIENT);
    if (patient != (patientId != null)) {
      throw invalid();
    }
    return new VerifiedIdentity(userId, roles, patientId);
  }

  /** The {@code X-User-Roles} value: role names joined with commas. */
  public String rolesHeaderValue() {
    return roles.stream().map(Role::name).collect(Collectors.joining(","));
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

  private static List<Role> roles(Object claim) {
    if (!(claim instanceof List<?> values) || values.isEmpty()) {
      throw invalid();
    }
    List<Role> roles = new ArrayList<>(values.size());
    Set<Role> seen = EnumSet.noneOf(Role.class);
    for (Object value : values) {
      Role role = role(value);
      if (!seen.add(role)) {
        throw invalid();
      }
      roles.add(role);
    }
    return roles;
  }

  private static Role role(Object value) {
    if (value instanceof String name) {
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
