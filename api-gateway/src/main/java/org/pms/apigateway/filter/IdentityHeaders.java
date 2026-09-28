package org.pms.apigateway.filter;

import java.util.Locale;

/**
 * The identity header contract between the gateway and downstream services. Downstream services
 * trust these headers only because the gateway removes every client-supplied copy and sets them
 * solely from a verified access token.
 */
public final class IdentityHeaders {

  /** The verified {@code sub} claim: the user's UUID. */
  public static final String USER_ID = "X-User-Id";

  /** The verified {@code roles} claim, comma-separated, no {@code ROLE_} prefix, no spaces. */
  public static final String USER_ROLES = "X-User-Roles";

  /** The verified {@code patientId} claim (UUID); present if and only if the role is PATIENT. */
  public static final String PATIENT_ID = "X-Patient-Id";

  private static final String USER_PREFIX = "x-user-";
  private static final String PATIENT_ID_LOWER = PATIENT_ID.toLowerCase(Locale.ROOT);

  private IdentityHeaders() {}

  /**
   * Whether a header belongs to the reserved identity namespace ({@code X-User-*} or {@code
   * X-Patient-Id}, any letter case) and so may only ever be set by the gateway.
   */
  public static boolean isReserved(String headerName) {
    String name = headerName.toLowerCase(Locale.ROOT);
    return name.startsWith(USER_PREFIX) || name.equals(PATIENT_ID_LOWER);
  }
}
