package org.pms.apigateway.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * One in-test Reactor Netty server standing in for everything behind the gateway: auth-service's
 * JWKS endpoint (counts requests, can be switched "down"), an attacker's key URL (counts requests;
 * must never be contacted), and both downstream services, which echo back as JSON the method, path
 * and every header they received (optionally after a delay). Started once per test JVM.
 */
public final class StubBackend {

  public static final String JWKS_PATH = "/.well-known/jwks.json";
  public static final String ATTACKER_PATH = "/attacker/jwks.json";
  public static final String DOWNSTREAM_404_PATH = "/api/v1/patients/downstream-404";
  public static final String DOWNSTREAM_404_BODY =
      "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"Patient not found\"}";

  private static final StubBackend INSTANCE = new StubBackend();

  private final JsonMapper json = JsonMapper.builder().build();
  private final AtomicInteger jwksRequests = new AtomicInteger();
  private final AtomicInteger attackerRequests = new AtomicInteger();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile JWKSet published;
  private volatile boolean jwksDown;
  private volatile Duration downstreamDelay;
  private final DisposableServer server;

  /** What a downstream received. Header names are lower-cased; all values are kept. */
  public record Received(String method, String uri, Map<String, List<String>> headers) {}

  private StubBackend() {
    reset();
    this.server = HttpServer.create().host("127.0.0.1").port(0).handle(this::handle).bindNow();
  }

  public static StubBackend get() {
    return INSTANCE;
  }

  /** Publishes only the primary key, JWKS up, no delay, counters and recordings cleared. */
  public void reset() {
    published = new JWKSet(TestKeys.PRIMARY.toPublicJWK());
    jwksDown = false;
    downstreamDelay = Duration.ZERO;
    received.clear();
  }

  /** Forgets what downstreams received so far (e.g. after a warm-up request). */
  public void clearReceived() {
    received.clear();
  }

  public String baseUri() {
    return "http://127.0.0.1:" + server.port();
  }

  public String jwksUri() {
    return baseUri() + JWKS_PATH;
  }

  public String attackerUri() {
    return baseUri() + ATTACKER_PATH;
  }

  public void publish(JWK... keys) {
    List<JWK> publicKeys = new ArrayList<>();
    for (JWK key : keys) {
      publicKeys.add(key.toPublicJWK());
    }
    published = new JWKSet(publicKeys);
  }

  public void jwksDown(boolean down) {
    jwksDown = down;
  }

  public void downstreamDelay(Duration delay) {
    downstreamDelay = delay;
  }

  public int jwksRequests() {
    return jwksRequests.get();
  }

  public int attackerRequests() {
    return attackerRequests.get();
  }

  public List<Received> received() {
    return List.copyOf(received);
  }

  /** A local port nothing listens on: connections to it are refused. */
  public static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Publisher<Void> handle(HttpServerRequest request, HttpServerResponse response) {
    String path = request.fullPath();
    if (path.equals(JWKS_PATH)) {
      jwksRequests.incrementAndGet();
      if (jwksDown) {
        return response.status(503).send();
      }
      return respondJson(response, published.toString());
    }
    if (path.equals(ATTACKER_PATH)) {
      attackerRequests.incrementAndGet();
      return respondJson(response, new JWKSet(TestKeys.ATTACKER.toPublicJWK()).toString());
    }
    Received record = record(request);
    received.add(record);
    if (path.equals(DOWNSTREAM_404_PATH)) {
      return response
          .status(404)
          .header("Content-Type", "application/problem+json")
          .sendString(Mono.just(DOWNSTREAM_404_BODY));
    }
    Map<String, Object> echo = new LinkedHashMap<>();
    echo.put("method", record.method());
    echo.put("uri", record.uri());
    echo.put("headers", record.headers());
    String body = json.writeValueAsString(echo);
    return request
        .receive()
        .then()
        .then(Mono.delay(downstreamDelay))
        .then(respondJson(response, body).then());
  }

  private static Mono<Void> respondJson(HttpServerResponse response, String body) {
    return response.header("Content-Type", "application/json").sendString(Mono.just(body)).then();
  }

  private static Received record(HttpServerRequest request) {
    Map<String, List<String>> headers = new TreeMap<>();
    request
        .requestHeaders()
        .forEach(
            entry ->
                headers
                    .computeIfAbsent(
                        entry.getKey().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(entry.getValue()));
    return new Received(request.method().name(), request.uri(), headers);
  }
}
