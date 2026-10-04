package org.pms.patientservice.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>{@code X-User-Roles} must hold exactly one role name (the V1 contract). A list, even of
 * duplicates, is rejected rather than merged: merging would let e.g. {@code PATIENT,ADMIN} pass
 * staff-only rules while still carrying a patient id.
 */
final class GatewayIdentityHeaders {

  static final String USER_ID = "X-User-Id";
  static final String USER_ROLES = "X-User-Roles";
  static final String PATIENT_ID = "X-Patient-Id";

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
    String role = singleValue(request, USER_ROLES);
    String patientId = singleValue(request, PATIENT_ID);
    if (userId == null && role == null && patientId == null) {
      return Optional.empty();
    }

    UUID parsedUserId = parseUuid(require(userId, USER_ID), USER_ID);
    Role parsedRole = parseRole(require(role, USER_ROLES));
    boolean isPatient = parsedRole == Role.PATIENT;
    if (isPatient && patientId == null) {
      throw new MalformedIdentityHeadersException(PATIENT_ID + " is required for the PATIENT role");
    }
    if (!isPatient && patientId != null) {
      throw new MalformedIdentityHeadersException(
          PATIENT_ID + " is only allowed for the PATIENT role");
    }
    UUID parsedPatientId = isPatient ? parseUuid(patientId, PATIENT_ID) : null;

    return Optional.of(new GatewayIdentity(parsedUserId, parsedRole, parsedPatientId));
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

  /**
   * Exactly one exact role name: no whitespace, no blanks, no list. Anything that is not a single
   * known name (including "DOCTOR,DOCTOR" or "PATIENT,ADMIN") is rejected.
   */
  private static Role parseRole(String value) {
    Role role = ROLES_BY_NAME.get(value);
    if (role == null) {
      throw new MalformedIdentityHeadersException(USER_ROLES + " must be exactly one known role");
    }
    return role;
  }

  /** Why a set of identity headers was rejected. The message never contains a header value. */
  static final class MalformedIdentityHeadersException extends RuntimeException {
    MalformedIdentityHeadersException(String reason) {
      super(reason, null, false, false);
    }
  }
}
