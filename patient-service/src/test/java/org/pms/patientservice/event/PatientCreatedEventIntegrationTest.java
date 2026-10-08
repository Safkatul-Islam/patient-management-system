package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.common.web.correlation.CorrelationIds;
import org.pms.patientservice.api.AbstractPatientApiTest;
import org.pms.patientservice.dto.PatientRequestDto;
import org.pms.patientservice.exception.EmailAlreadyExistsException;
import org.pms.patientservice.service.PatientService;
import org.pms.patientservice.support.TopicReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * patient.created against a real broker (Redpanda) and database: the record matches the contract
 * fixture, is published once and only after the patient is committed, and never for a create that
 * rolls back. Every "nothing was published" assertion sits next to a committed control create in
 * the same test, so it cannot pass just because publishing is broken.
 */
class PatientCreatedEventIntegrationTest extends AbstractPatientApiTest {

  private static final Path CONTRACT_FIXTURE =
      Path.of("..", "contracts", "events", "patient.created.v1.json");
  private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(15);

  /** Time given to a duplicate or a wrongly published record to show up before asserting. */
  private static final Duration SETTLE = Duration.ofSeconds(2);

  private static final Duration ROLLBACK_WINDOW = Duration.ofSeconds(5);
  private static final int CONCURRENT_CREATES = 8;

  @Autowired private KafkaConnectionDetails kafka;
  @Autowired private PatientCreatedTopicProperties topicProperties;
  @Autowired private PatientService patientService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JsonMapper jsonMapper;

  private TopicReader reader;

  @BeforeEach
  void startReading() {
    reader = TopicReader.fromNow(kafka.getBootstrapServers(), topicProperties.topic());
  }

  @AfterEach
  void stopReading() {
    reader.close();
  }

  @Test
  @DisplayName("the published record has the contract fixture's shape, key and headers")
  void recordMatchesTheContract() throws Exception {
    String patientId = createViaApi(uniqueEmail(), "contract-" + shortId());

    ConsumerRecord<String, String> record = reader.awaitKey(patientId, ARRIVAL_TIMEOUT).getFirst();

    JsonNode fixture = jsonMapper.readTree(Files.readString(CONTRACT_FIXTURE));
    JsonNode payload = jsonMapper.readTree(record.value());
    assertThat(payload.propertyNames()).hasSameElementsAs(fixture.propertyNames());
    for (String field : fixture.propertyNames()) {
      assertThat(payload.get(field).getNodeType())
          .as("type of %s", field)
          .isEqualTo(fixture.get(field).getNodeType());
    }
    assertThat(payload.propertyNames()).doesNotContain("name", "email", "dateOfBirth", "address");
    assertThat(payload.get("eventType").stringValue()).isEqualTo("patient.created");
    assertThat(payload.get("eventVersion").intValue()).isEqualTo(1);
    assertThat(UUID.fromString(payload.get("eventId").stringValue())).isNotNull();
    assertThat(Instant.parse(payload.get("occurredAt").stringValue())).isNotNull();
    assertThat(payload.get("occurredAt").stringValue()).endsWith("Z");
    assertThat(payload.get("patientId").stringValue()).isEqualTo(patientId);

    assertThat(record.key()).isEqualTo(patientId);
    // Only the correlation header: no __TypeId__ or other Java type information.
    assertThat(record.headers().toArray())
        .extracting(Header::key)
        .containsExactly(CorrelationIds.HEADER);
    assertThat(reader.partitionCount()).isEqualTo(topicProperties.partitions()).isEqualTo(3);
  }

  @Test
  @DisplayName("an API create publishes exactly one record, after commit, with its correlation ID")
  void apiCreatePublishesOnceWithCorrelationId() throws Exception {
    String correlationId = "after-commit-" + shortId();
    String patientId = createViaApi(uniqueEmail(), correlationId);

    List<ConsumerRecord<String, String>> records = reader.awaitKey(patientId, ARRIVAL_TIMEOUT);
    assertThat(records).isNotEmpty();
    // Visible from another connection the moment the record is consumed: committed.
    assertThat(rowCount(patientId)).isEqualTo(1);

    reader.pollFor(SETTLE);
    assertThat(reader.withKey(patientId)).hasSize(1);
    assertThat(headerValue(reader.withKey(patientId).getFirst())).isEqualTo(correlationId);
  }

  @Test
  @DisplayName("nothing is published while the creating transaction is still open")
  void publishesOnlyAfterTheSurroundingTransactionCommits() {
    AtomicReference<String> patientId = new AtomicReference<>();

    transactionTemplate.executeWithoutResult(
        status -> {
          patientId.set(patientService.createPatient(request(uniqueEmail())).getId());
          assertThat(reader.pollFor(SETTLE)).noneMatch(r -> patientId.get().equals(r.key()));
        });

    assertThat(reader.awaitKey(patientId.get(), ARRIVAL_TIMEOUT)).hasSize(1);
    assertThat(rowCount(patientId.get())).isEqualTo(1);
  }

  @Test
  @DisplayName("a rolled-back create publishes nothing, while a committed one publishes once")
  void rolledBackCreatePublishesNothing() {
    long rolledBackAt = System.nanoTime();
    AtomicReference<String> rolledBackId = new AtomicReference<>();
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      rolledBackId.set(
                          patientService.createPatient(request(uniqueEmail())).getId());
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(rowCount(rolledBackId.get())).isZero();

    String committedId = patientService.createPatient(request(uniqueEmail())).getId();

    assertThat(reader.awaitKey(committedId, ARRIVAL_TIMEOUT)).hasSize(1);
    Duration waited = Duration.ofNanos(System.nanoTime() - rolledBackAt);
    if (waited.compareTo(ROLLBACK_WINDOW) < 0) {
      reader.pollFor(ROLLBACK_WINDOW.minus(waited));
    }
    assertThat(reader.withKey(rolledBackId.get())).isEmpty();
    assertThat(reader.withKey(committedId)).hasSize(1);
  }

  @Test
  @DisplayName("concurrent same-email creates publish exactly one record, for the winner")
  void duplicateEmailRacePublishesOnlyTheWinner() throws Exception {
    String email = uniqueEmail();
    List<Object> outcomes = raceCreates(email);

    List<String> created =
        outcomes.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    List<Throwable> rejected =
        outcomes.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
    assertThat(created).hasSize(1);
    // A loser either fails the service's pre-check or, having passed it concurrently, is
    // rejected by the unique constraint when its transaction commits.
    assertThat(rejected)
        .hasSize(CONCURRENT_CREATES - 1)
        .allSatisfy(
            failure -> {
              if (!(failure instanceof EmailAlreadyExistsException)) {
                assertThat(failure).isExactlyInstanceOf(DataIntegrityViolationException.class);
                assertThat(constraintName(failure)).isEqualTo("patients_email_key");
              }
            });

    String winnerId = created.getFirst();
    assertThat(reader.awaitKey(winnerId, ARRIVAL_TIMEOUT)).hasSize(1);
    // Every record since the race started: losers' rolled-back inserts published nothing.
    assertThat(reader.pollFor(SETTLE)).extracting(ConsumerRecord::key).containsExactly(winnerId);
  }

  /** Runs concurrent creates; each outcome is the created id or the exception thrown. */
  private List<Object> raceCreates(String email) throws Exception {
    CountDownLatch ready = new CountDownLatch(CONCURRENT_CREATES);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CREATES);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CREATES; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    return patientService.createPatient(request(email)).getId();
                  } catch (RuntimeException failure) {
                    return failure;
                  }
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  private String createViaApi(String email, String correlationId) throws Exception {
    String response =
        mockMvc
            .perform(
                post(PATIENTS)
                    .with(asAdmin())
                    .header(CorrelationIds.HEADER, correlationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patientJson(email, "1990-01-01", "2024-01-01")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  private int rowCount(String patientId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM patients WHERE id = ?",
            Integer.class,
            UUID.fromString(patientId));
    return count == null ? 0 : count;
  }

  private static PatientRequestDto request(String email) {
    PatientRequestDto request = new PatientRequestDto();
    request.setName("Event Patient");
    request.setEmail(email);
    request.setAddress("1 Event St");
    request.setDateOfBirth("1990-01-01");
    request.setRegisteredDate("2024-01-01");
    return request;
  }

  private static String headerValue(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(CorrelationIds.HEADER);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static String constraintName(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
