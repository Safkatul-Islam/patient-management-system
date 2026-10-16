package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.TestKeys;
import org.pms.apigateway.support.ThreadDumps;
import org.pms.apigateway.support.Tokens;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** The bounded JWKS source seen from outside: fetch counts, cooldown, outage and key rotation. */
class JwksCacheTest extends AbstractGatewayTest {

  /** Past the configured 30s cooldown, so the next unknown kid may trigger one refetch. */
  private static final Duration PAST_COOLDOWN = Duration.ofSeconds(31);

  private static final int FLOOD_SIZE = 100;

  /** Each flood request gets this long, so a stall reports how many requests hung. */
  private static final Duration FLOOD_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** Recorded instead of a status for a flood request with no response in time. */
  private static final int HUNG = -1;

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

    Flood flood = flood();
    List<Integer> statuses = flood.statuses();

    int fetches = stub.jwksRequests() - before;
    System.out.println("JWKS fetches caused by " + FLOOD_SIZE + " unknown-kid tokens: " + fetches);
    assertThat(statuses).as(flood::report).hasSize(FLOOD_SIZE).containsOnly(401);
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

  /**
   * Sends {@value #FLOOD_SIZE} concurrent unknown-kid requests through a client with its own
   * connection pool, one connection per request, so the flood never queues for connections shared
   * with the rest of the test JVM. A request without a response within {@link
   * #FLOOD_REQUEST_TIMEOUT} is recorded as {@link #HUNG}, and the first such timeout captures a
   * thread dump while the stall is still in progress.
   */
  private Flood flood() {
    ConnectionProvider pool =
        ConnectionProvider.builder("jwks-flood")
            .maxConnections(FLOOD_SIZE)
            .pendingAcquireMaxCount(FLOOD_SIZE)
            .pendingAcquireTimeout(FLOOD_REQUEST_TIMEOUT)
            .build();
    try {
      WebClient client =
          WebClient.builder()
              .baseUrl("http://127.0.0.1:" + port)
              .clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool)))
              .build();
      AtomicReference<String> stallDump = new AtomicReference<>();
      List<Integer> statuses =
          Flux.range(0, FLOOD_SIZE)
              .map(i -> unknownKidToken())
              .flatMap(token -> status(client, bearer(token), stallDump), FLOOD_SIZE)
              .collectList()
              .block(FLOOD_REQUEST_TIMEOUT.plusSeconds(20));
      return new Flood(statuses, stallDump.get());
    } finally {
      pool.dispose();
    }
  }

  /** Non-blocking, so the flood really is concurrent. */
  private Mono<Integer> status(
      WebClient client, String authorization, AtomicReference<String> stallDump) {
    return client
        .get()
        .uri(PATIENTS)
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .exchangeToMono(
            response -> response.releaseBody().thenReturn(response.statusCode().value()))
        .timeout(FLOOD_REQUEST_TIMEOUT)
        .onErrorResume(
            TimeoutException.class,
            timeout -> {
              if (stallDump.get() == null) {
                stallDump.compareAndSet(null, ThreadDumps.eventLoopsAndJwks());
              }
              return Mono.just(HUNG);
            });
  }

  /** Per-request outcomes of a flood: a status code, or {@link #HUNG}. */
  private record Flood(List<Integer> statuses, String stallDump) {

    /** The assertion description, built only when the assertion fails. */
    String report() {
      long hung = statuses.stream().filter(status -> status == HUNG).count();
      String summary =
          (statuses.size() - hung)
              + " of "
              + FLOOD_SIZE
              + " flood requests completed, "
              + hung
              + " hung past "
              + FLOOD_REQUEST_TIMEOUT.toSeconds()
              + " s";
      return stallDump == null
          ? summary
          : summary + "\nThread dump at the first timeout:\n" + stallDump;
    }
  }

  private String unknownKidToken() {
    RSAKey key = TestKeys.PRIMARY;
    return Tokens.sign(
        key,
        Tokens.header("unknown-" + UUID.randomUUID()).build(),
        Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR").build());
  }
}
