package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.TestKeys;
import org.pms.apigateway.support.Tokens;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;

/** Logs never contain tokens or Authorization values, and request failure logs are correlated. */
@ExtendWith(OutputCaptureExtension.class)
class LoggingTest extends AbstractGatewayTest {

  @Test
  @DisplayName("No token or Authorization value in the logs; failure log lines carry correlationId")
  void noTokensInLogs(CapturedOutput output) {
    String valid = Tokens.validDoctor(clock);
    String forged =
        Tokens.sign(
            TestKeys.FOREIGN_SAME_KID,
            Tokens.staffClaims(clock, UUID.randomUUID(), "ADMIN").build());
    String unknownKid =
        Tokens.sign(
            TestKeys.PRIMARY,
            Tokens.header("unknown-" + UUID.randomUUID()).build(),
            Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").build());

    echo(getPatients(bearer(valid)));
    getPatients(bearer(forged)).expectStatus().isUnauthorized();
    stub.jwksDown(true);
    clock.advance(Duration.ofSeconds(31));
    getPatients(bearer(unknownKid)).expectStatus().isUnauthorized(); // logs a JWKS WARN
    stub.jwksDown(false);
    stub.downstreamDelay(Duration.ofSeconds(3));
    client
        .get()
        .uri(PATIENTS)
        .header(HttpHeaders.AUTHORIZATION, bearer(valid))
        .header("X-Correlation-Id", "corr-log-504")
        .exchange()
        .expectStatus()
        .isEqualTo(504);

    String logs = output.getAll();
    for (String token : List.of(valid, forged, unknownKid)) {
      assertThat(logs).doesNotContain(token);
      // Neither the signature nor the claims segment may appear on its own either.
      for (String part : token.split("\\.")) {
        assertThat(logs).doesNotContain(part);
      }
    }
    assertThat(logs).doesNotContain("Bearer ey");

    assertThat(logs).contains("JWK set fetch failed");
    List<String> failureLines =
        logs.lines().filter(line -> line.contains("Request failed with 504")).toList();
    assertThat(failureLines).hasSize(1);
    assertThat(failureLines.get(0)).contains("\"correlationId\":\"corr-log-504\"");
  }
}
