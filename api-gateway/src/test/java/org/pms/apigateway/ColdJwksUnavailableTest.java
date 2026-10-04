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

/**
 * No JWK set was ever obtained (auth-service unreachable since startup): a well-formed token gets
 * 503, not 401 and not 500. Own context, so the key cache is guaranteed cold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestClockConfig.class)
class ColdJwksUnavailableTest {

  private static final StubBackend stub = StubBackend.get();

  @Autowired private WebTestClient client;
  @Autowired private MutableClock clock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    GatewayTestProperties.register(
        registry,
        stub.baseUri(),
        stub.baseUri(),
        "http://127.0.0.1:" + StubBackend.closedPort() + StubBackend.JWKS_PATH);
  }

  @Test
  @DisplayName("Cold cache and JWKS unreachable: 503 problem, request not forwarded")
  void coldCacheUnavailable() {
    stub.reset();
    for (int attempt = 0; attempt < 2; attempt++) {
      client
          .get()
          .uri("/api/v1/patients")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + Tokens.validDoctor(clock))
          .header("X-Correlation-Id", "corr-cold")
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.title")
          .isEqualTo("Authentication temporarily unavailable")
          .jsonPath("$.correlationId")
          .isEqualTo("corr-cold");
    }
    assertThat(stub.received()).isEmpty();
  }

  @Test
  @DisplayName("Cold cache: an unparseable token is still a plain 401 (no key lookup needed)")
  void garbageTokenStill401() {
    stub.reset();
    client
        .get()
        .uri("/api/v1/patients")
        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    assertThat(stub.received()).isEmpty();
  }
}
