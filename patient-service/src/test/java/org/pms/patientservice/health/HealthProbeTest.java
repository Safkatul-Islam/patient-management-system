package org.pms.patientservice.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Only the health endpoint is exposed, with liveness and a DB-aware readiness probe. Probes carry
 * no identity headers, so every request here is sent without them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
class HealthProbeTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private HealthEndpointGroups healthGroups;
  @Autowired private WebEndpointsSupplier webEndpoints;

  @Test
  @DisplayName("liveness probe is UP")
  void livenessIsUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("readiness probe is UP with the database reachable")
  void readinessIsUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("readiness depends on the database, liveness does not")
  void readinessIncludesDatabase() {
    assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
    assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
  }

  @Test
  @DisplayName("health response hides component details")
  void healthHidesDetails() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(content().string(not(containsString("PostgreSQL"))));
  }

  /**
   * Security now answers 401 for any other actuator path before routing is reached, so the exposure
   * setting is checked directly rather than through a 404.
   */
  @Test
  @DisplayName("actuator endpoints other than health are not exposed")
  void otherEndpointsAreNotExposed() {
    assertThat(webEndpoints.getEndpoints())
        .extracting(endpoint -> endpoint.getEndpointId().toString())
        .containsExactly("health");
  }
}
