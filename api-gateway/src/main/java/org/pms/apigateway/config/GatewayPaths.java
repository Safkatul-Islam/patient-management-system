package org.pms.apigateway.config;

/**
 * The path patterns shared by the route table and the security rules, so a route and the rule that
 * protects it cannot drift apart. Matching is exact (no implicit trailing slash) on both sides.
 */
public final class GatewayPaths {

  /** POST only; reachable without an access token. */
  public static final String[] AUTH_PUBLIC = {"/auth/login", "/auth/refresh"};

  /** Require a verified access token; auth-service re-validates it and checks the role. */
  public static final String[] AUTH_PROTECTED = {"/auth/logout", "/auth/admin/**"};

  /** Require a verified access token; patient-service authorizes from the identity headers. */
  public static final String[] PATIENTS = {"/api/v1/patients", "/api/v1/patients/**"};

  /** The gateway's own liveness/readiness probes. */
  public static final String HEALTH = "/actuator/health/**";

  private GatewayPaths() {}
}
