package org.pms.apigateway.security.jwks;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.MutableClock;
import org.pms.apigateway.support.TestKeys;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The cache rules in isolation, with a fetch the test completes by hand. */
class BoundedJwkSourceTest {

  private static final Duration COOLDOWN = Duration.ofSeconds(30);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final ControlledFetcher fetcher = new ControlledFetcher();
  private final BoundedJwkSource source = new BoundedJwkSource(fetcher, clock, COOLDOWN);

  @Test
  @DisplayName("Concurrent requests during one fetch share it")
  void concurrentRequestsCoalesce() {
    List<Flux<JWK>> pending = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      pending.add(source.apply(token(TestKeys.PRIMARY_KID)).cache());
    }
    pending.forEach(Flux::subscribe);
    assertThat(fetcher.calls.get()).isEqualTo(1);

    fetcher.complete(new JWKSet(TestKeys.PRIMARY.toPublicJWK()));
    for (Flux<JWK> keys : pending) {
      StepVerifier.create(keys).expectNextCount(1).verifyComplete();
    }
    assertThat(fetcher.calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Unknown kid refetches only after the cooldown")
  void unknownKidRespectsCooldown() {
    warm();
    StepVerifier.create(source.apply(token("unknown"))).verifyComplete();
    assertThat(fetcher.calls.get()).isEqualTo(1);

    clock.advance(COOLDOWN.plusSeconds(1));
    Flux<JWK> refetch = source.apply(token("unknown")).cache();
    refetch.subscribe();
    assertThat(fetcher.calls.get()).isEqualTo(2);
    fetcher.complete(new JWKSet(TestKeys.PRIMARY.toPublicJWK()));
    StepVerifier.create(refetch).verifyComplete();
  }

  @Test
  @DisplayName("Cold cache and failed fetch: JwksUnavailableException; no refetch in cooldown")
  void coldFailure() {
    Flux<JWK> first = source.apply(token(TestKeys.PRIMARY_KID)).cache();
    first.subscribe(key -> {}, error -> {});
    fetcher.fail(new IllegalStateException("connection refused"));
    StepVerifier.create(first).expectError(JwksUnavailableException.class).verify();
    StepVerifier.create(source.apply(token(TestKeys.PRIMARY_KID)))
        .expectError(JwksUnavailableException.class)
        .verify();
    assertThat(fetcher.calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("Warm cache and failed refetch: known keys still served")
  void warmFailureKeepsCache() {
    warm();
    clock.advance(COOLDOWN.plusSeconds(1));
    Flux<JWK> unknown = source.apply(token("unknown")).cache();
    unknown.subscribe();
    fetcher.fail(new IllegalStateException("down"));
    StepVerifier.create(unknown).verifyComplete();
    StepVerifier.create(source.apply(token(TestKeys.PRIMARY_KID)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  @DisplayName("Non-RS256 or kid-less tokens never trigger a fetch")
  void nonRs256NeverFetches() {
    SignedJWT hs256 =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("x").build(),
            new JWTClaimsSet.Builder().build());
    SignedJWT noKid =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).build(), new JWTClaimsSet.Builder().build());
    StepVerifier.create(source.apply(hs256)).verifyComplete();
    StepVerifier.create(source.apply(noKid)).verifyComplete();
    assertThat(fetcher.calls.get()).isZero();
  }

  @Test
  @DisplayName("Keys that are not RSA-2048+ signing keys are never selected")
  void weakOrWrongKeysIgnored() throws Exception {
    RSAKey encryptionKey =
        new RSAKey.Builder(TestKeys.ROTATED.toRSAPublicKey())
            .keyID("enc")
            .keyUse(com.nimbusds.jose.jwk.KeyUse.ENCRYPTION)
            .build();
    Flux<JWK> keys = source.apply(token("enc")).cache();
    keys.subscribe();
    fetcher.complete(new JWKSet(encryptionKey));
    StepVerifier.create(keys).verifyComplete();
  }

  private void warm() {
    Flux<JWK> first = source.apply(token(TestKeys.PRIMARY_KID)).cache();
    first.subscribe();
    fetcher.complete(new JWKSet(TestKeys.PRIMARY.toPublicJWK()));
    StepVerifier.create(first).expectNextCount(1).verifyComplete();
  }

  private static SignedJWT token(String kid) {
    return new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
        new JWTClaimsSet.Builder().build());
  }

  /** Each call starts a fetch that stays pending until the test completes or fails it. */
  private static final class ControlledFetcher implements Supplier<Mono<JWKSet>> {

    private final AtomicInteger calls = new AtomicInteger();
    private Sinks.One<JWKSet> current;

    @Override
    public Mono<JWKSet> get() {
      calls.incrementAndGet();
      current = Sinks.one();
      return current.asMono();
    }

    void complete(JWKSet set) {
      current.tryEmitValue(set);
    }

    void fail(Throwable error) {
      current.tryEmitError(error);
    }
  }
}
