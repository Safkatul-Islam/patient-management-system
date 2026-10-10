package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.pms.patientservice.support.UnreachableBroker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The broker is unreachable (bootstrap address is a closed local port; no Redpanda imported), from
 * startup on: the context still starts (topic creation fails after the 5 s admin timeout), creates
 * succeed fast, every lost event is visible, and the service stays ready.
 */
@Import(PostgresTestcontainerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PatientCreatedBrokerDownTest extends AbstractLostEventTest {

  @Autowired private HealthEndpointGroups healthGroups;

  @DynamicPropertySource
  static void unreachableBroker(DynamicPropertyRegistry registry) {
    String address = UnreachableBroker.address();
    registry.add("spring.kafka.bootstrap-servers", () -> address);
  }

  @Test
  @Order(1)
  @DisplayName(
      "first create (cold producer) answers 201 fast; its lost event is logged and counted")
  void coldCreateSucceedsWhenTheBrokerIsDown(CapturedOutput output) throws Exception {
    createAndExpectOneLostEvent(output, "Broker down, cold producer");
  }

  @Test
  @Order(2)
  @DisplayName("a later create (warm producer) behaves the same")
  void warmCreateSucceedsWhenTheBrokerIsDown(CapturedOutput output) throws Exception {
    createAndExpectOneLostEvent(output, "Broker down, warm producer");
  }

  @Test
  @DisplayName("readiness stays UP and does not include Kafka while the broker is down")
  void readinessIgnoresTheBroker() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    assertThat(healthGroups.get("readiness").isMember("kafka")).isFalse();
  }
}
