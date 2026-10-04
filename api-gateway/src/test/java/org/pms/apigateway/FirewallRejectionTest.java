package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.RawHttp;
import org.pms.apigateway.support.Tokens;
import org.pms.common.web.correlation.CorrelationIds;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Paths that Spring Security's firewall rejects get the system's RFC 7807 body (not the default
 * bare 400), never echo the rejected path, and never reach a downstream.
 */
class FirewallRejectionTest extends AbstractGatewayTest {

  @LocalServerPort private int port;

  @ParameterizedTest(name = "{0} {1} token={2}")
  @CsvSource({
    "GET, //auth/admin/users, false",
    "GET, //auth/admin/users, true",
    "GET, /api/v1/patients/..;/..;/actuator/env, true",
    "GET, /api/v1/patients/%2e%2e/%2e%2e/actuator/env, true",
    "POST, /auth/login/../admin/users, false"
  })
  void firewallRejectionIsProblemJson(String method, String rawPath, boolean withToken)
      throws IOException {
    stub.clearReceived();
    String correlationId = "fw-" + UUID.randomUUID();
    Map<String, String> headers = new HashMap<>();
    headers.put(CorrelationIds.HEADER, correlationId);
    if (withToken) {
      headers.put("Authorization", bearer(Tokens.validDoctor(clock)));
    }

    RawHttp.Response response = RawHttp.send(port, method, rawPath, headers);

    assertThat(response.status()).isEqualTo(400);
    assertThat(MediaType.parseMediaType(response.headers().get("content-type")))
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.headers().get("x-correlation-id")).isEqualTo(correlationId);
    String body = response.body();
    assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("about:blank");
    assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Bad request");
    assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(400);
    assertThat(JsonPath.<String>read(body, "$.detail"))
        .isEqualTo("The request path is not allowed.");
    assertThat(JsonPath.<String>read(body, "$.correlationId")).isEqualTo(correlationId);
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKey("instance");
    assertThat(body).doesNotContain("admin").doesNotContain("actuator").doesNotContain("Exception");
    assertThat(stub.received()).isEmpty();
  }

  /**
   * A request target that is not a valid URI at all (here, backslashes) never becomes an exchange:
   * Spring's Reactor Netty adapter answers 400 itself, before any WebFilter (correlation ID,
   * security firewall) runs, so there is no problem body. Pinned here so the limit stays explicit.
   */
  @Test
  void unparseableRequestUriIsRejectedBeforeAnyFilter() throws IOException {
    stub.clearReceived();

    RawHttp.Response response =
        RawHttp.send(
            port,
            "GET",
            "/api/v1/patients\\..\\..\\actuator\\env",
            Map.of("Authorization", bearer(Tokens.validDoctor(clock))));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).isEmpty();
    assertThat(stub.received()).isEmpty();
  }
}
