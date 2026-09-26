package org.pms.patientservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pms.patientservice.security.GatewayIdentityHeaders.MalformedIdentityHeadersException;
import org.springframework.mock.web.MockHttpServletRequest;

/** Header parsing rules for the identity the API gateway forwards. */
class GatewayIdentityHeadersTest {

  private static final String USER_ID = "3f1c2a4e-8b7d-4c6a-9e5f-1a2b3c4d5e6f";
  private static final String PATIENT_ID = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d";

  @Test
  @DisplayName("a staff identity is parsed with its user id and role, and no patient id")
  void parsesStaffIdentity() {
    MockHttpServletRequest request = request(Map.of("X-User-Id", USER_ID, "X-User-Roles", "NURSE"));

    assertThat(GatewayIdentityHeaders.parse(request))
        .hasValueSatisfying(
            identity -> {
              assertThat(identity.userId()).isEqualTo(UUID.fromString(USER_ID));
              assertThat(identity.roles()).containsExactly(Role.NURSE);
              assertThat(identity.patientId()).isNull();
              assertThat(identity.isPatient()).isFalse();
            });
  }

  @Test
  @DisplayName("a PATIENT identity is parsed with the patient id it owns")
  void parsesPatientIdentity() {
    MockHttpServletRequest request =
        request(
            Map.of("X-User-Id", USER_ID, "X-User-Roles", "PATIENT", "X-Patient-Id", PATIENT_ID));

    assertThat(GatewayIdentityHeaders.parse(request))
        .hasValueSatisfying(
            identity -> {
              assertThat(identity.roles()).containsExactly(Role.PATIENT);
              assertThat(identity.patientId()).isEqualTo(UUID.fromString(PATIENT_ID));
              assertThat(identity.isPatient()).isTrue();
            });
  }

  @Test
  @DisplayName("several comma-separated roles are all parsed; a repeated role is harmless")
  void parsesSeveralRoles() {
    MockHttpServletRequest request =
        request(Map.of("X-User-Id", USER_ID, "X-User-Roles", "DOCTOR,ADMIN,DOCTOR"));

    assertThat(GatewayIdentityHeaders.parse(request))
        .hasValueSatisfying(
            identity -> assertThat(identity.roles()).isEqualTo(Set.of(Role.DOCTOR, Role.ADMIN)));
  }

  @Test
  @DisplayName("upper-case UUID digits are accepted")
  void acceptsUpperCaseUuid() {
    MockHttpServletRequest request =
        request(Map.of("X-User-Id", USER_ID.toUpperCase(), "X-User-Roles", "ADMIN"));

    assertThat(GatewayIdentityHeaders.parse(request))
        .hasValueSatisfying(
            identity -> assertThat(identity.userId()).isEqualTo(UUID.fromString(USER_ID)));
  }

  @Test
  @DisplayName("a request without any identity header is anonymous, not malformed")
  void noHeadersIsEmpty() {
    assertThat(GatewayIdentityHeaders.parse(new MockHttpServletRequest())).isEmpty();
  }

  static Stream<Arguments> malformedHeaders() {
    return Stream.of(
        malformed("user id missing", Map.of("X-User-Roles", "ADMIN")),
        malformed("user id not a UUID", Map.of("X-User-Id", "not-a-uuid", "X-User-Roles", "ADMIN")),
        malformed(
            "user id in non-canonical UUID form",
            Map.of("X-User-Id", "1-1-1-1-1", "X-User-Roles", "ADMIN")),
        malformed("user id empty", Map.of("X-User-Id", "", "X-User-Roles", "ADMIN")),
        malformed("roles missing", Map.of("X-User-Id", USER_ID)),
        malformed("roles empty", Map.of("X-User-Id", USER_ID, "X-User-Roles", "")),
        malformed("unknown role", Map.of("X-User-Id", USER_ID, "X-User-Roles", "SUPERUSER")),
        malformed("lower-case role", Map.of("X-User-Id", USER_ID, "X-User-Roles", "admin")),
        malformed(
            "ROLE_-prefixed role", Map.of("X-User-Id", USER_ID, "X-User-Roles", "ROLE_ADMIN")),
        malformed("padded role", Map.of("X-User-Id", USER_ID, "X-User-Roles", "ADMIN, DOCTOR")),
        malformed("trailing blank role", Map.of("X-User-Id", USER_ID, "X-User-Roles", "ADMIN,")),
        malformed(
            "blank role between", Map.of("X-User-Id", USER_ID, "X-User-Roles", "ADMIN,,NURSE")),
        malformed(
            "PATIENT without patient id", Map.of("X-User-Id", USER_ID, "X-User-Roles", "PATIENT")),
        malformed(
            "patient id without PATIENT",
            Map.of("X-User-Id", USER_ID, "X-User-Roles", "DOCTOR", "X-Patient-Id", PATIENT_ID)),
        malformed(
            "patient id not a UUID",
            Map.of("X-User-Id", USER_ID, "X-User-Roles", "PATIENT", "X-Patient-Id", "42")),
        malformed("only a patient id", Map.of("X-Patient-Id", PATIENT_ID)),
        Arguments.of(
            "user id repeated",
            (Consumer<MockHttpServletRequest>)
                request -> {
                  request.addHeader("X-User-Id", USER_ID);
                  request.addHeader("X-User-Id", UUID.randomUUID().toString());
                  request.addHeader("X-User-Roles", "ADMIN");
                }),
        Arguments.of(
            "roles repeated",
            (Consumer<MockHttpServletRequest>)
                request -> {
                  request.addHeader("X-User-Id", USER_ID);
                  request.addHeader("X-User-Roles", "NURSE");
                  request.addHeader("X-User-Roles", "ADMIN");
                }),
        Arguments.of(
            "patient id repeated",
            (Consumer<MockHttpServletRequest>)
                request -> {
                  request.addHeader("X-User-Id", USER_ID);
                  request.addHeader("X-User-Roles", "PATIENT");
                  request.addHeader("X-Patient-Id", PATIENT_ID);
                  request.addHeader("X-Patient-Id", UUID.randomUUID().toString());
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedHeaders")
  @DisplayName("an incomplete or malformed header set is rejected without echoing any value")
  void rejectsMalformedHeaders(String description, Consumer<MockHttpServletRequest> headers) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    headers.accept(request);

    assertThatThrownBy(() -> GatewayIdentityHeaders.parse(request))
        .isInstanceOf(MalformedIdentityHeadersException.class)
        .message()
        .doesNotContain(USER_ID)
        .doesNotContain(PATIENT_ID)
        .doesNotContain("not-a-uuid")
        .doesNotContain("SUPERUSER");
  }

  private static Arguments malformed(String description, Map<String, String> headers) {
    return Arguments.of(
        description,
        (Consumer<MockHttpServletRequest>) request -> headers.forEach(request::addHeader));
  }

  private static MockHttpServletRequest request(Map<String, String> headers) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    headers.forEach(request::addHeader);
    return request;
  }
}
