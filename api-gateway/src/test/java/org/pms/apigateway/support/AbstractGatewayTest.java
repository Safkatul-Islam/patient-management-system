package org.pms.apigateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.pms.common.web.correlation.CorrelationIds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The gateway on a random port in front of {@link StubBackend}. Every subclass shares one context
 * (one gateway, one JWKS cache, one clock that only moves forward), so each test sets up the stub
 * state it relies on and never assumes a cold or warm cache unless it arranges it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestClockConfig.class)
public abstract class AbstractGatewayTest {

  protected static final StubBackend stub = StubBackend.get();

  protected static final String PATIENTS = "/api/v1/patients";

  @Autowired protected WebTestClient client;

  @Autowired protected MutableClock clock;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    GatewayTestProperties.register(registry, stub.baseUri(), stub.baseUri(), stub.jwksUri());
  }

  @BeforeEach
  void resetStub() {
    stub.reset();
  }

  protected static String bearer(String token) {
    return "Bearer " + token;
  }

  /** GET on the patients route with the given Authorization value. */
  protected WebTestClient.ResponseSpec getPatients(String authorization) {
    return client.get().uri(PATIENTS).header(HttpHeaders.AUTHORIZATION, authorization).exchange();
  }

  /** Asserts a 401 problem body and that nothing reached any downstream. */
  protected void assertRejected(WebTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader()
        .valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.title")
        .isEqualTo("Unauthorized")
        .jsonPath("$.detail")
        .isEqualTo("A valid bearer access token is required.")
        .jsonPath("$.correlationId")
        .isNotEmpty();
    assertThat(stub.received()).as("downstream must not be called").isEmpty();
  }

  /** The echo body of a forwarded request: what the downstream received. */
  protected static Echo echo(WebTestClient.ResponseSpec response) {
    Map<String, Object> body =
        response
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();
    assertThat(body).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, List<String>> headers = (Map<String, List<String>>) body.get("headers");
    return new Echo((String) body.get("method"), (String) body.get("uri"), headers);
  }

  /** What a downstream received, as echoed back. Header names are lower-case. */
  protected record Echo(String method, String uri, Map<String, List<String>> headers) {

    public List<String> header(String name) {
      return headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    public String correlationId() {
      List<String> values = header(CorrelationIds.HEADER);
      assertThat(values).hasSize(1);
      return values.get(0);
    }
  }
}
