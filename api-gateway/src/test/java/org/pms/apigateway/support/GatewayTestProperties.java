package org.pms.apigateway.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points the gateway at the stub backend. Test classes needing a different wiring pass overrides.
 */
public final class GatewayTestProperties {

  /** Short enough for the timeout test to stay fast. */
  public static final String RESPONSE_TIMEOUT = "1s";

  private GatewayTestProperties() {}

  public static void register(
      DynamicPropertyRegistry registry, String authUri, String patientUri, String jwksUri) {
    registry.add("pms.gateway.auth-service-uri", () -> authUri);
    registry.add("pms.gateway.patient-service-uri", () -> patientUri);
    registry.add("pms.gateway.jwt.jwks.uri", () -> jwksUri);
    registry.add(
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> RESPONSE_TIMEOUT);
    // Tests start the gateway on a random port on loopback.
    registry.add("server.address", () -> "127.0.0.1");
  }
}
