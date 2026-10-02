package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.StubBackend;
import org.pms.apigateway.support.Tokens;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Path tricks against the protected and unrouted surface, sent over a raw socket so no client
 * normalizes or re-encodes the request line. Each request must either be rejected (400, 401, 403,
 * 404) without reaching any downstream, or be forwarded with a clean literal path that the security
 * rule for its route covers: never an actuator or admin path smuggled through another route, and
 * never a protected path without a token.
 */
class PathNormalizationTest extends AbstractGatewayTest {

  private static final List<Integer> REJECTED = List.of(400, 401, 403, 404);

  /** Segments a downstream could decode or normalize into a different path. */
  private static final Pattern CLEAN_SEGMENT = Pattern.compile("[A-Za-z0-9_~-]+(\\.[A-Za-z0-9]+)?");

  @LocalServerPort private int port;

  static Stream<Arguments> tricks() {
    return Stream.of(
        Arguments.of("GET", "/api/v1/patients/%2e%2e/%2e%2e/actuator/env", true),
        Arguments.of("GET", "/api/v1/patients/%2e%2e/%2e%2e/%2e%2e/actuator/env", true),
        Arguments.of("GET", "/api/v1/patients/..%2f..%2factuator/env", true),
        Arguments.of("GET", "/api/v1/patients/%2F..%2Factuator", true),
        Arguments.of("GET", "/api/v1/patients/%252e%252e/%252e%252e/actuator/env", true),
        Arguments.of("GET", "/api/v1/patients/../../auth/admin/users", true),
        Arguments.of("GET", "/api/v1/patients/./../../actuator/env", true),
        Arguments.of("GET", "/api/v1/patients/..;/..;/actuator/env", true),
        Arguments.of("GET", "/api/v1/patients/%2e%2e;/actuator", true),
        Arguments.of("GET", "/api/v1/patients\\..\\..\\actuator\\env", true),
        Arguments.of("GET", "//auth/admin/users", false),
        Arguments.of("GET", "//auth/admin/users", true),
        Arguments.of("GET", "//api/v1/patients", false),
        Arguments.of("GET", "/auth/admin;x=y/users", false),
        Arguments.of("GET", "/auth/admin;x=y/users", true),
        Arguments.of("GET", "/auth/admin/users;jsessionid=x", false),
        Arguments.of("GET", "/auth/admin/users;jsessionid=x", true),
        Arguments.of("GET", "/AUTH/ADMIN/users", false),
        Arguments.of("GET", "/AUTH/ADMIN/users", true),
        Arguments.of("GET", "/API/V1/PATIENTS", true),
        Arguments.of("POST", "/auth/login/../admin/users", false),
        Arguments.of("POST", "/auth/login/%2e%2e/admin/users", false),
        Arguments.of("POST", "/auth/login;/../admin/users", false),
        Arguments.of("POST", "/auth/login/", false),
        Arguments.of("GET", "/auth/admin/users/", false),
        Arguments.of("GET", "/api/v1/patients/", false),
        Arguments.of("GET", "/api/v1/patients/", true),
        Arguments.of("GET", "/actuator/health/../env", false),
        Arguments.of("GET", "/actuator/health/%2e%2e/env", false),
        Arguments.of("GET", "/actuator/health/..%2fenv", false));
  }

  @ParameterizedTest(name = "{0} {1} token={2}")
  @MethodSource("tricks")
  void pathTrickIsRejectedOrSafelyForwarded(String method, String rawPath, boolean withToken)
      throws IOException {
    stub.clearReceived();
    int status = send(method, rawPath, withToken ? Tokens.validDoctor(clock) : null);
    List<StubBackend.Received> received = stub.received();
    List<String> forwarded = received.stream().map(StubBackend.Received::uri).toList();
    System.out.printf(
        "F4 %s %s token=%s -> %d forwarded=%s%n", method, rawPath, withToken, status, forwarded);

    if (forwarded.isEmpty()) {
      assertThat(status).as("not forwarded, so must be a rejection").isIn(REJECTED);
      return;
    }
    assertThat(forwarded).hasSize(1);
    String path = forwarded.get(0);
    assertThat(path).as("forwarded unchanged").isEqualTo(rawPath);
    assertThat(isCleanPath(path)).as("clean literal path: %s", path).isTrue();
    assertThat(coveredBySecurityRule(method, path, withToken))
        .as("security rule for the route covers %s %s (token=%s)", method, path, withToken)
        .isTrue();
  }

  /** Only plain segments: no dot segments, encodings, matrix parameters or backslashes. */
  private static boolean isCleanPath(String path) {
    if (!path.startsWith("/") || path.startsWith("//")) {
      return false;
    }
    String trimmed = path.endsWith("/") ? path.substring(1, path.length() - 1) : path.substring(1);
    for (String segment : trimmed.split("/", -1)) {
      if (!CLEAN_SEGMENT.matcher(segment).matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean coveredBySecurityRule(String method, String path, boolean withToken) {
    boolean publicAuth =
        "POST".equals(method) && (path.equals("/auth/login") || path.equals("/auth/refresh"));
    boolean patients = path.equals("/api/v1/patients") || path.startsWith("/api/v1/patients/");
    boolean authProtected = path.equals("/auth/logout") || path.startsWith("/auth/admin/");
    return publicAuth || (withToken && (patients || authProtected));
  }

  /** One HTTP/1.1 request with the request line exactly as given; returns the status code. */
  private int send(String method, String rawPath, String token) throws IOException {
    StringBuilder request = new StringBuilder();
    request.append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n");
    request.append("Host: 127.0.0.1:").append(port).append("\r\n");
    request.append("Connection: close\r\n");
    if (token != null) {
      request.append("Authorization: ").append(bearer(token)).append("\r\n");
    }
    if ("POST".equals(method)) {
      request.append("Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
    } else {
      request.append("\r\n");
    }
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
      out.flush();
      String response = readAll(socket.getInputStream());
      String statusLine = response.lines().findFirst().orElseThrow();
      return Integer.parseInt(statusLine.split(" ")[1]);
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) != -1) {
      bytes.write(buffer, 0, read);
    }
    return bytes.toString(StandardCharsets.ISO_8859_1);
  }
}
