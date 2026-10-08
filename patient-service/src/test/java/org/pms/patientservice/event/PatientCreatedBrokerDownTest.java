package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The broker is unreachable (bootstrap address is a closed local port; no Redpanda imported): the
 * patient is still created and the request answers 201 within the producer's bounds, the lost event
 * is visible as one ERROR line with identifiers only plus the failure counter, and the service
 * stays ready. PIPELINE.md: "Failure is visible; V1 may lose that event".
 *
 * <p>Startup itself runs with the broker down too; the admin timeout is shortened only so the
 * failed topic creation does not cost this test Boot's default 30 s.
 */
@SpringBootTest(properties = "spring.kafka.admin.operation-timeout=2s")
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PatientCreatedBrokerDownTest {

  /** max.block.ms is 2 s; anything near Kafka's 60 s default would fail this. */
  private static final Duration MAX_RESPONSE_TIME = Duration.ofSeconds(4);

  private static final String NAME = "Outage Synthetic";
  private static final String ADDRESS = "77 Brokerless Lane";
  private static final String DATE_OF_BIRTH = "1984-02-29";

  private static final Pattern FAILURE_LINE =
      Pattern.compile("Failed to publish patient.created eventId=([0-9a-f-]{36}) patientId=(\\S+)");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private HealthEndpointGroups healthGroups;

  @DynamicPropertySource
  static void unreachableBroker(DynamicPropertyRegistry registry) {
    int port = closedPort();
    registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:" + port);
  }

  @Test
  @DisplayName("create answers 201 promptly, commits, and logs and counts the lost event")
  void createSucceedsWhenTheBrokerIsDown(CapturedOutput output) throws Exception {
    String email = "outage-" + UUID.randomUUID() + "@example.com";

    long started = System.nanoTime();
    String response =
        mockMvc
            .perform(
                post("/api/v1/patients")
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Roles", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"%s","email":"%s","address":"%s",\
                        "dateOfBirth":"%s","registeredDate":"2024-01-01"}"""
                            .formatted(NAME, email, ADDRESS, DATE_OF_BIRTH)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    System.out.println("Broker-down create response time: " + elapsed.toMillis() + " ms");

    assertThat(elapsed).isLessThan(MAX_RESPONSE_TIME);
    String patientId = JsonPath.read(response, "$.id");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patients WHERE id = ?",
                Integer.class,
                UUID.fromString(patientId)))
        .isEqualTo(1);

    await().atMost(Duration.ofSeconds(15)).until(() -> failures() >= 1);
    assertThat(failures()).isEqualTo(1);

    List<String> failureLines =
        output.getAll().lines().filter(line -> FAILURE_LINE.matcher(line).find()).toList();
    assertThat(failureLines).hasSize(1);
    Matcher matcher = FAILURE_LINE.matcher(failureLines.getFirst());
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(2)).isEqualTo(patientId);
    assertThat(failureLines.getFirst()).contains("ERROR");
    assertThat(output.getAll()).doesNotContain(NAME, email, ADDRESS, DATE_OF_BIRTH);
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

  private double failures() {
    return meterRegistry
        .get(PatientCreatedPublisher.FAILURE_COUNTER)
        .tag("eventType", "patient.created")
        .counter()
        .count();
  }

  /** A local port nothing listens on: bound once to pick it, then released. */
  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
