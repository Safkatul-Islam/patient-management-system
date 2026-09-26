package org.pms.patientservice.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the identity the API gateway forwards after validating the caller's access token. The
 * gateway strips client-supplied copies of these headers first, and patient-service is not publicly
 * reachable, so a well-formed set is trusted as-is.
 *
 * <p>Parsing is strict: anything but a complete, well-formed set is rejected as a whole. Rejection
 * reasons name headers, never their values.
 */
final class GatewayIdentityHeaders {

  static final String USER_ID = "X-User-Id";
  static final String USER_ROLES = "X-User-Roles";
  static final String PATIENT_ID = "X-Patient-Id";

  private static final String ROLE_SEPARATOR = ",";

  /** {@link UUID#fromString} also accepts non-canonical forms such as {@code 1-1-1-1-1}. */
  private static final Pattern CANONICAL_UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final Map<String, Role> ROLES_BY_NAME =
      Arrays.stream(Role.values()).collect(Collectors.toUnmodifiableMap(Role::name, r -> r));

  private GatewayIdentityHeaders() {}

  /**
   * @return empty when the request carries none of the identity headers
   * @throws MalformedIdentityHeadersException when any identity header is present but the set is
   *     incomplete or malformed
   */
  static Optional<GatewayIdentity> parse(HttpServletRequest request) {
    String userId = singleValue(request, USER_ID);
    String roles = singleValue(request, USER_ROLES);
    String patientId = singleValue(request, PATIENT_ID);
    if (userId == null && roles == null && patientId == null) {
      return Optional.empty();
    }

    UUID parsedUserId = parseUuid(require(userId, USER_ID), USER_ID);
    Set<Role> parsedRoles = parseRoles(require(roles, USER_ROLES));
    boolean isPatient = parsedRoles.contains(Role.PATIENT);
    if (isPatient && patientId == null) {
      throw new MalformedIdentityHeadersException(PATIENT_ID + " is required for the PATIENT role");
    }
    if (!isPatient && patientId != null) {
      throw new MalformedIdentityHeadersException(
          PATIENT_ID + " is only allowed for the PATIENT role");
    }
    UUID parsedPatientId = isPatient ? parseUuid(patientId, PATIENT_ID) : null;

    return Optional.of(new GatewayIdentity(parsedUserId, parsedRoles, parsedPatientId));
  }

  /** The header's only value, or null when absent. Repeated headers are ambiguous and rejected. */
  private static String singleValue(HttpServletRequest request, String header) {
    Enumeration<String> values = request.getHeaders(header);
    if (values == null || !values.hasMoreElements()) {
      return null;
    }
    String value = values.nextElement();
    if (values.hasMoreElements()) {
      throw new MalformedIdentityHeadersException(header + " must have exactly one value");
    }
    return value;
  }

  private static String require(String value, String header) {
    if (value == null) {
      throw new MalformedIdentityHeadersException(header + " is missing");
    }
    return value;
  }

  private static UUID parseUuid(String value, String header) {
    if (!CANONICAL_UUID.matcher(value).matches()) {
      throw new MalformedIdentityHeadersException(header + " is not a valid UUID");
    }
    return UUID.fromString(value);
  }

  /** Comma-separated exact role names; no whitespace, no blanks (duplicates are harmless). */
  private static Set<Role> parseRoles(String value) {
    Set<Role> roles = EnumSet.noneOf(Role.class);
    // A negative limit keeps trailing empty entries, so "ADMIN," is rejected as a blank role.
    for (String name : value.split(ROLE_SEPARATOR, -1)) {
      Role role = ROLES_BY_NAME.get(name);
      if (role == null) {
        throw new MalformedIdentityHeadersException(
            USER_ROLES + " contains a blank or unknown role");
      }
      roles.add(role);
    }
    return roles;
  }

  /** Why a set of identity headers was rejected. The message never contains a header value. */
  static final class MalformedIdentityHeadersException extends RuntimeException {
    MalformedIdentityHeadersException(String reason) {
      super(reason, null, false, false);
    }
  }
}
