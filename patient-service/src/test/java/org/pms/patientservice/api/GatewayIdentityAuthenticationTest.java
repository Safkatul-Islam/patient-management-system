package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Requests without a complete, well-formed gateway identity are unauthenticated: a 401 problem with
 * a bearer challenge, never a hint about which header was wrong, and no header value in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class GatewayIdentityAuthenticationTest extends AbstractPatientApiTest {

  private static final String DETAIL = "A verified identity from the API gateway is required.";
  private static final String CORRELATION_HEADER = "X-Correlation-Id";

  /** Distinctive values, so the log check below cannot match anything else by accident. */
  private static final String USER_ID = UUID.randomUUID().toString();

  private static final String PATIENT_ID = UUID.randomUUID().toString();
  private static final String BAD_UUID = "not-a-uuid-" + UUID.randomUUID();
  private static final String UNKNOWN_ROLE = "SUPERUSER_" + System.nanoTime();

  @Test
  @DisplayName("no identity headers: 401 problem with a Bearer challenge and the correlation id")
  void missingIdentityIs401Problem() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get(PATIENTS))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.detail").value(DETAIL))
            .andExpect(jsonPath("$.instance").value(PATIENTS))
            .andReturn();

    String correlationId = result.getResponse().getHeader(CORRELATION_HEADER);
    assertThat(correlationId).isNotBlank();
    assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.correlationId"))
        .isEqualTo(correlationId);
  }

  static Stream<Arguments> malformedIdentities() {
    return Stream.of(
        identity("user id is not a UUID", BAD_UUID, "ADMIN", null),
        identity("unknown role", USER_ID, UNKNOWN_ROLE, null),
        identity("empty roles", USER_ID, "", null),
        identity("roles missing", USER_ID, null, null),
        identity("two staff roles", USER_ID, "DOCTOR,ADMIN", null),
        identity("a role repeated", USER_ID, "DOCTOR,DOCTOR", null),
        identity("PATIENT plus a staff role", USER_ID, "PATIENT,ADMIN", PATIENT_ID),
        identity("user id missing", null, "ADMIN", null),
        identity("PATIENT without X-Patient-Id", USER_ID, "PATIENT", null),
        identity("X-Patient-Id without PATIENT", USER_ID, "DOCTOR", PATIENT_ID),
        identity("X-Patient-Id is not a UUID", USER_ID, "PATIENT", BAD_UUID),
        Arguments.of(
            "X-User-Id sent twice",
            (RequestPostProcessor)
                request -> {
                  request.addHeader(USER_ID_HEADER, USER_ID);
                  request.addHeader(USER_ID_HEADER, UUID.randomUUID().toString());
                  request.addHeader(USER_ROLES_HEADER, "ADMIN");
                  return request;
                }),
        Arguments.of(
            "X-User-Roles sent twice",
            (RequestPostProcessor)
                request -> {
                  request.addHeader(USER_ID_HEADER, USER_ID);
                  request.addHeader(USER_ROLES_HEADER, "BILLING_STAFF");
                  request.addHeader(USER_ROLES_HEADER, "ADMIN");
                  return request;
                }),
        Arguments.of(
            "X-Patient-Id sent twice",
            (RequestPostProcessor)
                request -> {
                  request.addHeader(USER_ID_HEADER, USER_ID);
                  request.addHeader(USER_ROLES_HEADER, "PATIENT");
                  request.addHeader(PATIENT_ID_HEADER, PATIENT_ID);
                  request.addHeader(PATIENT_ID_HEADER, UUID.randomUUID().toString());
                  return request;
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedIdentities")
  @DisplayName("malformed identity headers: the same 401 problem, and no header value is logged")
  void malformedIdentityIs401WithoutLoggingValues(
      String description, RequestPostProcessor identity, CapturedOutput output) throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(identity))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(DETAIL))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());

    // The rejection is logged (so a misbehaving gateway is visible), but only by reason.
    assertThat(output.getAll())
        .contains("Rejected gateway identity headers")
        .doesNotContain(USER_ID)
        .doesNotContain(PATIENT_ID)
        .doesNotContain(BAD_UUID)
        .doesNotContain(UNKNOWN_ROLE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
  @DisplayName("health probes are reachable without an identity")
  void healthProbesNeedNoIdentity(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("health probes still answer when stray malformed identity headers are present")
  void healthProbesIgnoreMalformedIdentity() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness").header(USER_ID_HEADER, BAD_UUID))
        .andExpect(status().isOk());
  }

  /**
   * V1 callers hold exactly one role. A combined PATIENT,ADMIN identity must not borrow the staff
   * role to list patients or read another record; it is not authenticated at all.
   */
  @Test
  @DisplayName("PATIENT combined with ADMIN is a 401 on the list and on another patient's record")
  void patientCombinedWithAdminIsUnauthenticated() throws Exception {
    String ownId = createPatient(uniqueEmail());
    String otherId = createPatient(uniqueEmail());
    RequestPostProcessor patientAndAdmin = roles("PATIENT,ADMIN", ownId);

    mockMvc
        .perform(get(PATIENTS).with(patientAndAdmin))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.detail").value(DETAIL))
        .andExpect(jsonPath("$.content").doesNotExist());
    mockMvc
        .perform(get(PATIENTS + "/" + otherId).with(patientAndAdmin))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value(DETAIL))
        .andExpect(jsonPath("$.email").doesNotExist());
  }

  @Test
  @DisplayName("DOCTOR combined with ADMIN is a 401 on delete, and the record survives")
  void doctorCombinedWithAdminCannotDelete() throws Exception {
    String id = createPatient(uniqueEmail());

    mockMvc
        .perform(delete(PATIENTS + "/" + id).with(roles("DOCTOR,ADMIN", null)))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.detail").value(DETAIL));
    mockMvc.perform(get(PATIENTS + "/" + id).with(asAdmin())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("unlisted paths are denied: 401 without an identity, 403 even for an ADMIN")
  void unlistedPathsAreDenied() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/actuator/env").with(asAdmin()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mockMvc.perform(get("/api/v2/patients").with(asAdmin())).andExpect(status().isForbidden());
  }

  /** A fresh user id with the given raw X-User-Roles value and optional X-Patient-Id. */
  private static RequestPostProcessor roles(String roles, String patientId) {
    return request -> {
      request.addHeader(USER_ID_HEADER, UUID.randomUUID().toString());
      request.addHeader(USER_ROLES_HEADER, roles);
      if (patientId != null) {
        request.addHeader(PATIENT_ID_HEADER, patientId);
      }
      return request;
    };
  }

  private static Arguments identity(
      String description, String userId, String roles, String patientId) {
    RequestPostProcessor headers =
        request -> {
          if (userId != null) {
            request.addHeader(USER_ID_HEADER, userId);
          }
          if (roles != null) {
            request.addHeader(USER_ROLES_HEADER, roles);
          }
          if (patientId != null) {
            request.addHeader(PATIENT_ID_HEADER, patientId);
          }
          return request;
        };
    return Arguments.of(description, headers);
  }
}
