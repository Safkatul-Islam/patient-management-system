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
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Only the health endpoint is exposed, with liveness and a DB-aware readiness probe. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
class HealthProbeTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private HealthEndpointGroups healthGroups;

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

  @Test
  @DisplayName("actuator endpoints other than health are not exposed")
  void otherEndpointsAreNotExposed() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
  }
}
