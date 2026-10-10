package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A create whose event cannot be published (subclasses decide why): the request still answers 201
 * fast, because it never waits on Kafka, the patient is committed, and the lost event shows up
 * once, asynchronously, as the failure counter and exactly one ERROR log event carrying identifiers
 * only. PIPELINE.md: "Failure is visible; V1 may lose that event".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractLostEventTest {

  /**
   * The request does not wait on Kafka, whatever the broker's state. Kept below the producer's
   * max.block.ms (2 s), which is what a send on the request thread would add, but with headroom for
   * the first request of a cold test JVM (measured at 1.4 s once, with Kafka not involved).
   */
  static final Duration FAST_RESPONSE = Duration.ofMillis(1500);

  private static final Logger log = LoggerFactory.getLogger(AbstractLostEventTest.class);

  private static final String NAME = "Outage Synthetic";
  private static final String ADDRESS = "77 Brokerless Lane";
  private static final String DATE_OF_BIRTH = "1984-02-29";

  @Autowired MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  /**
   * Measures how long each send() blocks its caller; otherwise behaves normally. Typed {@code <?,
   * ?>} because that is the type Boot's bean method declares; a spy of {@code KafkaTemplate<String,
   * String>} finds no bean to wrap.
   */
  @MockitoSpyBean private KafkaTemplate<?, ?> kafkaTemplate;

  /** Creates one patient and checks its event is lost visibly; logs the measured timings. */
  void createAndExpectOneLostEvent(CapturedOutput output, String scenario) throws Exception {
    int outputStart = output.getAll().length();
    double failuresBefore = sendFailures();
    List<Duration> sendBlocking = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              long started = System.nanoTime();
              try {
                return invocation.callRealMethod();
              } finally {
                sendBlocking.add(Duration.ofNanos(System.nanoTime() - started));
              }
            })
        .when(kafkaTemplate)
        .send(any(ProducerRecord.class));
    String email = "lost-" + UUID.randomUUID() + "@example.com";

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
    Duration httpTime = Duration.ofNanos(System.nanoTime() - started);
    String patientId = JsonPath.read(response, "$.id");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patients WHERE id = ?",
                Integer.class,
                UUID.fromString(patientId)))
        .isEqualTo(1);

    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> sendFailures() >= failuresBefore + 1 && !sendBlocking.isEmpty());
    log.info(
        "{}: send() blocked {} ms; HTTP create {} ms",
        scenario,
        sendBlocking.getFirst().toMillis(),
        httpTime.toMillis());
    assertThat(httpTime).isLessThan(FAST_RESPONSE);
    assertThat(sendFailures()).isEqualTo(failuresBefore + 1);
    assertThat(sendBlocking).hasSize(1);

    String captured = output.getAll().substring(outputStart);
    assertThat(captured.lines().filter(line -> line.contains("\"level\":\"ERROR\"")))
        .as("ERROR log events for one lost event")
        .singleElement(STRING)
        .containsPattern("Failed to publish patient.created eventId=[0-9a-f-]{36} patientId=")
        .contains("patientId=" + patientId + " reason=send_failed");
    assertThat(captured)
        .doesNotContain("payload=", "Exception thrown when sending a message")
        .doesNotContain(NAME, email, ADDRESS, DATE_OF_BIRTH);
  }

  private double sendFailures() {
    return meterRegistry
        .get(PatientCreatedPublisher.FAILURE_COUNTER)
        .tag("eventType", "patient.created")
        .tag("reason", PatientCreatedPublisher.SEND_FAILED)
        .counter()
        .count();
  }
}
