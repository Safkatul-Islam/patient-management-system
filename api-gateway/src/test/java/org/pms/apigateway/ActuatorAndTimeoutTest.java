package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.Tokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The gateway's own probes, and a downstream slower than the response timeout. */
class ActuatorAndTimeoutTest extends AbstractGatewayTest {

  @Test
  @DisplayName("Liveness and readiness are public and UP, with no downstream components")
  void probesUp() {
    client
        .get()
        .uri("/actuator/health/liveness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
    client
        .get()
        .uri("/actuator/health/readiness")
        .header(HttpHeaders.AUTHORIZATION, bearer("garbage-is-ignored-on-public-paths"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP")
        .jsonPath("$.components")
        .doesNotExist();
  }

  @Test
  @DisplayName("Readiness stays UP while downstreams are slow or down")
  void readinessIndependentOfDownstreams() {
    stub.jwksDown(true);
    client
        .get()
        .uri("/actuator/health/readiness")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  @DisplayName("Other actuator endpoints are not reachable, with or without a token")
  void otherActuatorEndpointsDenied() {
    assertRejected(client.get().uri("/actuator/env").exchange());
    assertRejected(client.get().uri("/actuator/gateway/routes").exchange());
    assertRejected(client.get().uri("/actuator").exchange());
    client
        .get()
        .uri("/actuator/env")
        .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("Downstream slower than the response timeout: 504 problem, no internals leaked")
  void downstreamTimeout() {
    stub.downstreamDelay(Duration.ofSeconds(3));
    String body =
        client
            .get()
            .uri(PATIENTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
            .header("X-Correlation-Id", "corr-504")
            .exchange()
            .expectStatus()
            .isEqualTo(504)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(body)
        .contains(
            "\"status\":504", "\"correlationId\":\"corr-504\"", "\"instance\":\"/api/v1/patients\"")
        .doesNotContain("127.0.0.1", "Exception", "timeout:", "http://");
  }
}
