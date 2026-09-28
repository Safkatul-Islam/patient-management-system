package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.TestKeys;
import org.pms.apigateway.support.Tokens;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The bounded JWKS source seen from outside: fetch counts, cooldown, outage and key rotation. */
class JwksCacheTest extends AbstractGatewayTest {

  /** Past the configured 30s cooldown, so the next unknown kid may trigger one refetch. */
  private static final Duration PAST_COOLDOWN = Duration.ofSeconds(31);

  private static final int FLOOD_SIZE = 100;

  @LocalServerPort private int port;

  @Test
  @DisplayName("Known kid is served from cache: repeated valid requests do not refetch")
  void knownKidServedFromCache() {
    echo(getPatients(bearer(Tokens.validDoctor(clock))));
    int before = stub.jwksRequests();
    for (int i = 0; i < 20; i++) {
      echo(getPatients(bearer(Tokens.validDoctor(clock))));
    }
    assertThat(stub.jwksRequests()).isEqualTo(before);
  }

  @Test
  @DisplayName("100 concurrent unknown-kid tokens within the cooldown cause at most one fetch")
  void unknownKidFloodIsBounded() {
    echo(getPatients(bearer(Tokens.validDoctor(clock)))); // warm
    clock.advance(PAST_COOLDOWN); // a refetch is now allowed, exactly once
    stub.clearReceived();
    int before = stub.jwksRequests();

    List<Integer> statuses =
        Flux.range(0, FLOOD_SIZE)
            .map(i -> unknownKidToken())
            .flatMap(token -> status(bearer(token)), FLOOD_SIZE)
            .collectList()
            .block(Duration.ofSeconds(30));

    int fetches = stub.jwksRequests() - before;
    System.out.println("JWKS fetches caused by " + FLOOD_SIZE + " unknown-kid tokens: " + fetches);
    assertThat(statuses).hasSize(FLOOD_SIZE).containsOnly(401);
    assertThat(fetches).isLessThanOrEqualTo(1);
    assertThat(stub.received()).isEmpty();

    // Still within the cooldown: a second wave causes no fetch at all.
    for (int i = 0; i < 10; i++) {
      assertRejected(getPatients(bearer(unknownKidToken())));
    }
    assertThat(stub.jwksRequests() - before).isEqualTo(fetches);
  }

  @Test
  @DisplayName("JWKS down with a warm cache: valid tokens still work, unknown kids get 401")
  void warmCacheSurvivesJwksOutage() {
    echo(getPatients(bearer(Tokens.validDoctor(clock)))); // warm
    stub.jwksDown(true);
    clock.advance(PAST_COOLDOWN);
    stub.clearReceived();
    int before = stub.jwksRequests();

    assertRejected(getPatients(bearer(unknownKidToken()))); // refetch attempted and failed
    assertThat(stub.jwksRequests()).isEqualTo(before + 1);
    echo(getPatients(bearer(Tokens.validDoctor(clock)))); // old key still trusted
    assertThat(stub.jwksRequests()).isEqualTo(before + 1);
  }

  @Test
  @DisplayName("Key rotation: a new kid published in JWKS is accepted after the cooldown")
  void keyRotation() {
    echo(getPatients(bearer(Tokens.validDoctor(clock)))); // warm with the primary key only
    stub.publish(TestKeys.PRIMARY, TestKeys.ROTATED);
    clock.advance(PAST_COOLDOWN);

    String rotated =
        Tokens.sign(
            TestKeys.ROTATED, Tokens.staffClaims(clock, UUID.randomUUID(), "NURSE").build());
    Echo echo = echo(getPatients(bearer(rotated)));
    assertThat(echo.header("X-User-Roles")).containsExactly("NURSE");
    echo(getPatients(bearer(Tokens.validDoctor(clock)))); // primary still valid
  }

  /** Non-blocking, so the flood really is concurrent. */
  private Mono<Integer> status(String authorization) {
    return WebClient.create("http://127.0.0.1:" + port)
        .get()
        .uri(PATIENTS)
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .exchangeToMono(
            response -> response.releaseBody().thenReturn(response.statusCode().value()));
  }

  private String unknownKidToken() {
    RSAKey key = TestKeys.PRIMARY;
    return Tokens.sign(
        key,
        Tokens.header("unknown-" + UUID.randomUUID()).build(),
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").build());
  }
}
