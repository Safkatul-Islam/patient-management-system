package org.pms.authservice.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIdFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ActuatorAndCorrelationTest extends AbstractAuthApiTest {

  @Test
  @DisplayName("Liveness and readiness probes are public and UP")
  void probesAreUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  @DisplayName("Other actuator endpoints are not reachable")
  void otherActuatorEndpointsDenied() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("A supplied X-Correlation-Id is echoed back")
  void correlationIdEchoed() throws Exception {
    mockMvc
        .perform(get("/.well-known/jwks.json").header(CorrelationIdFilter.HEADER, "corr-echo-1"))
        .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-echo-1"));
  }

  @Test
  @DisplayName("401 from the security chain carries the correlation id")
  void unauthorizedBodyHasCorrelationId() throws Exception {
    mockMvc
        .perform(
            post("/auth/admin/users")
                .header(CorrelationIdFilter.HEADER, "corr-401")
                .contentType(MediaType.APPLICATION_JSON)
                .content(staffJson(uniqueEmail(), randomPassword(), "NURSE")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-401"))
        .andExpect(jsonPath("$.correlationId").value("corr-401"));
  }

  @Test
  @DisplayName("403 from the security chain carries the correlation id")
  void forbiddenBodyHasCorrelationId() throws Exception {
    String doctorAccess = JsonPath.read(newDoctorSession(), "$.accessToken");

    mockMvc
        .perform(
            post("/auth/admin/users")
                .header(CorrelationIdFilter.HEADER, "corr-403")
                .header(HttpHeaders.AUTHORIZATION, bearer(doctorAccess))
                .contentType(MediaType.APPLICATION_JSON)
                .content(staffJson(uniqueEmail(), randomPassword(), "NURSE")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.correlationId").value("corr-403"));
  }
}
