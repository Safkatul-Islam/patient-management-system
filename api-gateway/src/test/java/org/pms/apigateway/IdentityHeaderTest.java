package org.pms.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.apigateway.support.AbstractGatewayTest;
import org.pms.apigateway.support.StubBackend;
import org.pms.apigateway.support.TestKeys;
import org.pms.apigateway.support.Tokens;
import org.pms.common.web.correlation.CorrelationIds;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trust boundary: what downstream services receive. Identity headers come only from the
 * verified token, raw tokens never reach patient-service, and client-supplied forwarding and
 * identity headers are dropped.
 */
class IdentityHeaderTest extends AbstractGatewayTest {

  @LocalServerPort private int port;

  private final UUID userId = UUID.randomUUID();
  private final UUID patientId = UUID.randomUUID();

  private String patientToken() {
    return Tokens.sign(TestKeys.PRIMARY, Tokens.patientClaims(clock, userId, patientId).build());
  }

  @Test
  @DisplayName("Spoofed identity headers are replaced by exactly the verified values")
  void spoofedIdentityHeadersReplaced() {
    Echo echo =
        echo(
            client
                .get()
                .uri(PATIENTS + "/" + patientId)
                .header(HttpHeaders.AUTHORIZATION, bearer(patientToken()))
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("x-user-roles", "ADMIN")
                .header("X-Patient-Id", UUID.randomUUID().toString(), UUID.randomUUID().toString())
                .header("X-User-Anything", "spoofed")
                .exchange());

    assertThat(echo.header("X-User-Id")).containsExactly(userId.toString());
    assertThat(echo.header("X-User-Roles")).containsExactly("PATIENT");
    assertThat(echo.header("X-Patient-Id")).containsExactly(patientId.toString());
    assertThat(echo.header("X-User-Anything")).isEmpty();
    assertThat(echo.uri()).isEqualTo(PATIENTS + "/" + patientId);
  }

  @Test
  @DisplayName("Differently-cased duplicate identity headers on the wire are all removed")
  void mixedCaseDuplicatesRemoved() throws Exception {
    String body =
        HttpClient.create()
            .headers(
                headers ->
                    headers
                        .add(HttpHeaders.AUTHORIZATION, bearer(patientToken()))
                        .add("x-user-roles", "ADMIN")
                        .add("X-USER-ROLES", "ADMIN")
                        .add("X-User-Roles", "ADMIN")
                        .add("x-patient-id", UUID.randomUUID().toString())
                        .add("X-PATIENT-ID", UUID.randomUUID().toString())
                        .add("x-user-id", UUID.randomUUID().toString()))
            .get()
            .uri("http://127.0.0.1:" + port + PATIENTS)
            .responseSingle(
                (response, content) -> {
                  assertThat(response.status().code()).isEqualTo(200);
                  return content.asString();
                })
            .block(Duration.ofSeconds(10));

    @SuppressWarnings("unchecked")
    Map<String, List<String>> headers =
        (Map<String, List<String>>)
            JsonMapper.builder().build().readValue(body, Map.class).get("headers");
    assertThat(headers.get("x-user-roles")).containsExactly("PATIENT");
    assertThat(headers.get("x-patient-id")).containsExactly(patientId.toString());
    assertThat(headers.get("x-user-id")).containsExactly(userId.toString());
  }

  @Test
  @DisplayName("Underscore and mixed underscore/hyphen identity header variants are all removed")
  void underscoreVariantsRemoved() {
    String body =
        HttpClient.create()
            .headers(
                headers ->
                    headers
                        .add(HttpHeaders.AUTHORIZATION, bearer(patientToken()))
                        .add("X_User_Id", UUID.randomUUID().toString())
                        .add("x_user_roles", "ADMIN")
                        .add("X_USER_ROLES", "ADMIN")
                        .add("X_Patient_Id", UUID.randomUUID().toString())
                        .add("x_patient_id", UUID.randomUUID().toString())
                        .add("X-User_Id", UUID.randomUUID().toString())
                        .add("X_User-Roles", "ADMIN")
                        .add("X-Patient_Id", UUID.randomUUID().toString())
                        .add("X_User_Anything", "spoofed"))
            .get()
            .uri("http://127.0.0.1:" + port + PATIENTS)
            .responseSingle(
                (response, content) -> {
                  assertThat(response.status().code()).isEqualTo(200);
                  return content.asString();
                })
            .block(Duration.ofSeconds(10));

    @SuppressWarnings("unchecked")
    Map<String, List<String>> headers =
        (Map<String, List<String>>)
            JsonMapper.builder().build().readValue(body, Map.class).get("headers");
    // Only the gateway's verified, hyphenated headers remain.
    assertThat(headers.keySet())
        .filteredOn(
            name ->
                name.replace('_', '-').startsWith("x-user-")
                    || name.replace('_', '-').equals("x-patient-id"))
        .containsExactlyInAnyOrder("x-user-id", "x-user-roles", "x-patient-id");
    assertThat(headers.get("x-user-id")).containsExactly(userId.toString());
    assertThat(headers.get("x-user-roles")).containsExactly("PATIENT");
    assertThat(headers.get("x-patient-id")).containsExactly(patientId.toString());
  }

  @Test
  @DisplayName("Underscore identity header variants are stripped on public routes too")
  void underscoreVariantsRemovedOnPublicRoute() {
    String body =
        HttpClient.create()
            .headers(
                headers ->
                    headers
                        .add("Content-Type", "application/json")
                        .add("X_User_Id", UUID.randomUUID().toString())
                        .add("x_user_roles", "ADMIN")
                        .add("X_Patient_Id", UUID.randomUUID().toString()))
            .post()
            .uri("http://127.0.0.1:" + port + "/auth/login")
            .send(ByteBufFlux.fromString(Mono.just("{\"email\":\"dr.example@pms.test\"}")))
            .responseSingle(
                (response, content) -> {
                  assertThat(response.status().code()).isEqualTo(200);
                  return content.asString();
                })
            .block(Duration.ofSeconds(10));

    @SuppressWarnings("unchecked")
    Map<String, List<String>> headers =
        (Map<String, List<String>>)
            JsonMapper.builder().build().readValue(body, Map.class).get("headers");
    assertThat(headers.keySet())
        .noneMatch(
            name ->
                name.replace('_', '-').startsWith("x-user-")
                    || name.replace('_', '-').equals("x-patient-id"));
  }

  @Test
  @DisplayName("Staff token: no X-Patient-Id, even when the client sends one")
  void staffTokenHasNoPatientId() {
    Echo echo =
        echo(
            client
                .get()
                .uri(PATIENTS)
                .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
                .header("X-Patient-Id", UUID.randomUUID().toString())
                .exchange());
    assertThat(echo.header("X-User-Roles")).containsExactly("DOCTOR");
    assertThat(echo.header("X-Patient-Id")).isEmpty();
  }

  @Test
  @DisplayName("patient-service never receives the Authorization header")
  void authorizationRemovedForPatientService() {
    Echo echo = echo(getPatients(bearer(Tokens.validDoctor(clock))));
    assertThat(echo.header(HttpHeaders.AUTHORIZATION)).isEmpty();
  }

  @Test
  @DisplayName("auth-service protected routes receive the Authorization header and identity")
  void authorizationForwardedToAuthService() {
    String token = Tokens.validDoctor(clock);
    Echo echo =
        echo(
            client
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"synthetic\"}")
                .exchange());
    assertThat(echo.method()).isEqualTo("POST");
    assertThat(echo.uri()).isEqualTo("/auth/logout");
    assertThat(echo.header(HttpHeaders.AUTHORIZATION)).containsExactly(bearer(token));
    assertThat(echo.header("X-User-Roles")).containsExactly("DOCTOR");

    Echo admin =
        echo(
            client
                .get()
                .uri("/auth/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .exchange());
    assertThat(admin.uri()).isEqualTo("/auth/admin/users");
    assertThat(admin.header(HttpHeaders.AUTHORIZATION)).containsExactly(bearer(token));
  }

  @Test
  @DisplayName("Public login route: spoofed identity headers are stripped too")
  void publicRouteStripsIdentityHeaders() {
    Echo echo =
        echo(
            client
                .post()
                .uri("/auth/login")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", "ADMIN")
                .header("X-Patient-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"dr.example@pms.test\",\"password\":\"synthetic\"}")
                .exchange());
    assertThat(echo.uri()).isEqualTo("/auth/login");
    assertThat(echo.headers().keySet())
        .noneMatch(name -> name.startsWith("x-user-") || name.equals("x-patient-id"));
  }

  @Test
  @DisplayName("Public refresh route ignores an expired bearer token and still forwards")
  void publicRouteIgnoresExpiredBearer() {
    String expired =
        Tokens.sign(
            TestKeys.PRIMARY,
            Tokens.staffClaims(clock, UUID.randomUUID(), "DOCTOR")
                .expirationTime(Date.from(clock.instant().minus(Duration.ofHours(1))))
                .build());
    Echo echo =
        echo(
            client
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, bearer(expired))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"synthetic\"}")
                .exchange());
    assertThat(echo.uri()).isEqualTo("/auth/refresh");
  }

  @Test
  @DisplayName("Public routes are POST only: GET /auth/login is denied, not forwarded")
  void publicRouteMethodRestricted() {
    assertRejected(client.get().uri("/auth/login").exchange());
  }

  @Test
  @DisplayName("No token on a protected route: 401, downstream not called")
  void noTokenRejected() {
    assertRejected(client.get().uri(PATIENTS).exchange());
    assertRejected(client.post().uri("/auth/logout").exchange());
  }

  @Test
  @DisplayName("A well-formed client correlation ID is forwarded and echoed")
  void correlationIdForwarded() {
    WebTestClient.ResponseSpec response =
        client
            .get()
            .uri(PATIENTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
            .header(CorrelationIds.HEADER, "corr-gw-1")
            .exchange();
    response.expectHeader().valueEquals(CorrelationIds.HEADER, "corr-gw-1");
    assertThat(echo(response).correlationId()).isEqualTo("corr-gw-1");
  }

  @Test
  @DisplayName("A malformed correlation ID is replaced by a generated one, consistently")
  void malformedCorrelationIdReplaced() {
    WebTestClient.ResponseSpec response =
        client
            .get()
            .uri(PATIENTS)
            .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
            .header(CorrelationIds.HEADER, "bad id!")
            .exchange();
    String generated =
        response.returnResult(String.class).getResponseHeaders().getFirst(CorrelationIds.HEADER);
    assertThat(generated).isNotEqualTo("bad id!");
    assertThat(UUID.fromString(generated)).isNotNull();
    assertThat(stub.received()).hasSize(1);
    assertThat(stub.received().get(0).headers().get("x-correlation-id")).containsExactly(generated);
  }

  @Test
  @DisplayName("Error bodies carry the correlation ID")
  void errorBodyHasCorrelationId() {
    client
        .get()
        .uri(PATIENTS)
        .header(CorrelationIds.HEADER, "corr-401")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals(CorrelationIds.HEADER, "corr-401")
        .expectBody()
        .jsonPath("$.correlationId")
        .isEqualTo("corr-401")
        .jsonPath("$.instance")
        .isEqualTo(PATIENTS);
  }

  @Test
  @DisplayName("Client Forwarded / X-Forwarded-* headers are not propagated")
  void forwardedHeadersNotPropagated() {
    Echo echo =
        echo(
            client
                .get()
                .uri(PATIENTS)
                .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
                .header("X-Forwarded-For", "203.0.113.66")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-Prefix", "/evil")
                .header("Forwarded", "for=203.0.113.66;host=evil.example;proto=https")
                .exchange());
    assertThat(echo.headers().keySet())
        .noneMatch(name -> name.startsWith("x-forwarded-") || name.equals("forwarded"));
  }

  @Test
  @DisplayName("Downstream error responses pass through untouched")
  void downstreamErrorPassesThrough() {
    client
        .get()
        .uri(StubBackend.DOWNSTREAM_404_PATH)
        .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .json(StubBackend.DOWNSTREAM_404_BODY);
  }

  @Test
  @DisplayName("Unrouted paths fail closed: 401 without a token, 403 with a valid one")
  void unroutedPathsFailClosed() {
    assertRejected(client.get().uri("/unknown").exchange());
    assertRejected(client.get().uri(StubBackend.JWKS_PATH).exchange());
    client
        .get()
        .uri("/unknown")
        .header(HttpHeaders.AUTHORIZATION, bearer(Tokens.validDoctor(clock)))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(403)
        .jsonPath("$.correlationId")
        .isNotEmpty();
    // A trailing slash is not an implicit match for the exact /auth/login pattern.
    assertRejected(client.post().uri("/auth/login/").exchange());
    assertThat(stub.received()).isEmpty();
  }
}
