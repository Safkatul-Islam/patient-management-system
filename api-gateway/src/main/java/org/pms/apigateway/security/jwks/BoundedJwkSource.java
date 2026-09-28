package org.pms.apigateway.security.jwks;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Supplies candidate verification keys for a token, with bounded network use.
 *
 * <p>Spring's {@code withJwkSetUri} source refetches the key set for every token whose {@code kid}
 * it has not cached, before any signature check, so unauthenticated junk tokens could make the
 * gateway call auth-service once each. This source instead:
 *
 * <ul>
 *   <li>serves known key ids from the cached set, with no network call;
 *   <li>refetches for an unknown key id only when the last fetch attempt is older than the
 *       cooldown, and coalesces concurrent refetches into one call;
 *   <li>keeps the last good set when a refetch fails, so valid tokens keep working through an
 *       auth-service outage;
 *   <li>fails with {@link JwksUnavailableException} (a 503) only when no set was ever obtained.
 * </ul>
 *
 * <p>Keys are selected by the token's {@code kid} and must be RSA, 2048 bits or more, with {@code
 * use} sig or absent and {@code alg} RS256 or absent. Key material referenced from the token itself
 * ({@code jwk}, {@code jku}, {@code x5u}, {@code x5c}) is never used or fetched.
 */
public class BoundedJwkSource implements Function<SignedJWT, Flux<JWK>> {

  private static final Logger log = LoggerFactory.getLogger(BoundedJwkSource.class);
  private static final int MIN_RSA_KEY_BITS = 2048;

  private final Supplier<Mono<JWKSet>> fetcher;
  private final Clock clock;
  private final Duration cooldown;

  private final Object lock = new Object();
  // All three guarded by lock.
  private JWKSet cached;
  private Instant lastAttempt;
  private Mono<JWKSet> inFlight;

  public BoundedJwkSource(Supplier<Mono<JWKSet>> fetcher, Clock clock, Duration cooldown) {
    this.fetcher = fetcher;
    this.clock = clock;
    this.cooldown = cooldown;
  }

  @Override
  public Flux<JWK> apply(SignedJWT jwt) {
    JWSHeader header = jwt.getHeader();
    String kid = header.getKeyID();
    // Only RS256 tokens with a key id can ever verify; nothing else may trigger a fetch.
    if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || kid == null || kid.isBlank()) {
      return Flux.empty();
    }
    return Mono.defer(() -> keysFor(kid)).flatMapMany(Flux::fromIterable);
  }

  private Mono<List<JWK>> keysFor(String kid) {
    Mono<JWKSet> refresh;
    boolean warm;
    synchronized (lock) {
      warm = cached != null;
      if (warm) {
        List<JWK> keys = select(cached, kid);
        if (!keys.isEmpty()) {
          return Mono.just(keys);
        }
      }
      refresh = refreshIfAllowed();
    }
    if (refresh == null) {
      return warm
          ? Mono.just(List.of())
          : Mono.error(new JwksUnavailableException("No JWK set available yet"));
    }
    return refresh
        .map(set -> select(set, kid))
        // A failed refetch with a warm cache means "unknown key" (401), not an outage (503).
        .onErrorResume(ex -> warm ? Mono.just(List.of()) : Mono.error(ex));
  }

  /** Called with {@code lock} held. Returns the shared refresh, or null while cooling down. */
  private Mono<JWKSet> refreshIfAllowed() {
    if (inFlight != null) {
      return inFlight;
    }
    Instant now = clock.instant();
    if (lastAttempt != null && now.isBefore(lastAttempt.plus(cooldown))) {
      return null;
    }
    lastAttempt = now;
    // Subscribed here, once, rather than by the first caller: every caller of this attempt gets
    // the same outcome, and a cancelled caller cannot leave the attempt stuck in flight.
    Sinks.One<JWKSet> outcome = Sinks.one();
    inFlight = outcome.asMono();
    fetcher
        .get()
        .switchIfEmpty(Mono.error(() -> new JwksUnavailableException("No JWK set returned")))
        .subscribe(set -> onFetched(set, outcome), error -> onFetchFailed(error, outcome));
    return outcome.asMono();
  }

  private void onFetched(JWKSet set, Sinks.One<JWKSet> outcome) {
    synchronized (lock) {
      cached = set;
      inFlight = null;
    }
    log.info("Fetched JWK set with {} key(s)", set.getKeys().size());
    outcome.tryEmitValue(set);
  }

  private void onFetchFailed(Throwable error, Sinks.One<JWKSet> outcome) {
    boolean warm;
    synchronized (lock) {
      warm = cached != null;
      inFlight = null;
    }
    // Types only (messages can quote response bodies); no token content ever reaches here.
    log.warn(
        "JWK set fetch failed ({}, root cause {}); {}",
        error.getClass().getSimpleName(),
        rootCause(error).getClass().getName(),
        warm ? "keeping the cached key set" : "no key set cached yet");
    outcome.tryEmitError(
        error instanceof JwksUnavailableException
            ? error
            : new JwksUnavailableException("JWK set fetch failed", error));
  }

  private static Throwable rootCause(Throwable error) {
    Throwable root = error;
    for (int depth = 0; depth < 16 && root.getCause() != null && root.getCause() != root; depth++) {
      root = root.getCause();
    }
    return root;
  }

  private static List<JWK> select(JWKSet set, String kid) {
    JWKMatcher matcher =
        new JWKMatcher.Builder()
            .keyType(KeyType.RSA)
            .keyID(kid)
            .keyUses(KeyUse.SIGNATURE, null)
            .algorithms(JWSAlgorithm.RS256, null)
            .minKeySize(MIN_RSA_KEY_BITS)
            .build();
    return new JWKSelector(matcher).select(set);
  }
}
