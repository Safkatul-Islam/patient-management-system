package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.GatewayTestProperties;
import org.pms.apigateway.support.MutableClock;
import org.pms.apigateway.support.StubBackend;
import org.pms.apigateway.support.TestClockConfig;
import org.pms.apigateway.support.Tokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** patient-service unreachable (connection refused): 503 problem, not 500. Own context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestClockConfig.class)
class DownstreamUnavailableTest {

  private static final StubBackend stub = StubBackend.get();

  @Autowired private WebTestClient client;
  @Autowired private MutableClock clock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    GatewayTestProperties.register(
        registry, stub.baseUri(), "http://127.0.0.1:" + StubBackend.closedPort(), stub.jwksUri());
  }

  @Test
  @DisplayName("Downstream connection refused: 503 problem with correlation ID, no internals")
  void downstreamDown() {
    stub.reset();
    String body =
        client
            .get()
            .uri("/api/v1/patients")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.validDoctor(clock))
            .header("X-Correlation-Id", "corr-503")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader()
            .valueEquals("X-Correlation-Id", "corr-503")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(body)
        .contains(
            "\"status\":503", "\"title\":\"Service unavailable\"", "\"correlationId\":\"corr-503\"")
        .doesNotContain("127.0.0.1", "Exception", "Connection refused", "http://");
  }
}
