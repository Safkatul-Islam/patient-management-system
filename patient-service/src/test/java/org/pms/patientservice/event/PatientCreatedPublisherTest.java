package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PatientCreatedPublisherTest {

  private static final String TOPIC = "patient.created";
  private static final String SECRET_DETAIL = "broker-said-something-long";
  private static final Duration WAIT = Duration.ofSeconds(5);

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PatientCreated event = newEvent();

  private PatientCreatedPublisher publisher;

  @AfterEach
  void stopPublisher() {
    if (publisher != null) {
      publisher.destroy();
    }
  }

  @Test
  void sendsTheV1PayloadOnTheSenderThreadWithTheCorrelationId() {
    AtomicReference<String> sendingThread = new AtomicReference<>();
    AtomicReference<String> mdcDuringSend = new AtomicReference<>();
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenAnswer(
            invocation -> {
              sendingThread.set(Thread.currentThread().getName());
              mdcDuringSend.set(MDC.get(CorrelationIds.MDC_KEY));
              return new CompletableFuture<>();
            });
    publisher = newPublisher(10, WAIT);

    publisher.onPatientCreated(event);

    ProducerRecord<String, String> sent = sentRecord();
    assertThat(sendingThread.get()).isEqualTo("patient-events-sender");
    assertThat(mdcDuringSend.get()).isEqualTo("corr-42");
    assertThat(sent.topic()).isEqualTo(TOPIC);
    assertThat(sent.key()).isEqualTo(event.patientId().toString());
    assertThat(sent.headers().toArray())
        .singleElement()
        .satisfies(
            header -> {
              assertThat(header.key()).isEqualTo(CorrelationIds.HEADER);
              assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("corr-42");
            });

    JsonNode payload = jsonMapper.readTree(sent.value());
    assertThat(payload.propertyNames())
        .containsExactlyInAnyOrder(
            "eventId", "eventType", "eventVersion", "occurredAt", "patientId");
    assertThat(payload.get("eventId").stringValue()).isEqualTo(event.eventId().toString());
    assertThat(payload.get("eventType").stringValue()).isEqualTo("patient.created");
    assertThat(payload.get("eventVersion").isInt()).isTrue();
    assertThat(payload.get("eventVersion").intValue()).isEqualTo(1);
    assertThat(payload.get("occurredAt").stringValue()).isEqualTo("2026-10-07T12:34:56.789Z");
    assertThat(payload.get("patientId").stringValue()).isEqualTo(event.patientId().toString());
  }

  @Test
  void successfulSendCountsNoFailure() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));
    publisher = newPublisher(10, WAIT);

    publisher.onPatientCreated(event);
    sentRecord();
    publisher.destroy();

    assertThat(totalFailures()).isZero();
  }

  @Test
  void synchronousSendFailureIsLoggedAndCountedNotThrown(CapturedOutput output) {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenThrow(new KafkaException(SECRET_DETAIL));
    publisher = newPublisher(10, WAIT);

    assertThatNoException().isThrownBy(() -> publisher.onPatientCreated(event));

    await().atMost(WAIT).until(() -> failures(PatientCreatedPublisher.SEND_FAILED) == 1);
    assertFailureLogged(output, event, "send_failed", KafkaException.class);
  }

  @Test
  void asynchronousSendFailureIsLoggedAndCountedOnce(CapturedOutput output) {
    CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ack);
    publisher = newPublisher(10, WAIT);

    publisher.onPatientCreated(event);
    ProducerRecord<String, String> sent = sentRecord();
    assertThat(totalFailures()).isZero();

    ack.completeExceptionally(
        new KafkaProducerException(sent, SECRET_DETAIL, new TimeoutException()));

    assertThat(failures(PatientCreatedPublisher.SEND_FAILED)).isEqualTo(1);
    assertThat(totalFailures()).isEqualTo(1);
    assertFailureLogged(output, event, "send_failed", TimeoutException.class);
  }

  @Test
  void eventIsDroppedAndCountedWhenTheQueueIsFull(CapturedOutput output) throws Exception {
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenAnswer(
            invocation -> {
              sending.countDown();
              release.await();
              return new CompletableFuture<>();
            });
    publisher = newPublisher(1, WAIT);

    publisher.onPatientCreated(newEvent());
    assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
    publisher.onPatientCreated(newEvent()); // waits in the one queue slot
    PatientCreated dropped = newEvent();
    publisher.onPatientCreated(dropped);

    assertThat(failures(PatientCreatedPublisher.QUEUE_FULL)).isEqualTo(1);
    assertFailureLogged(output, dropped, "queue_full", null);
    release.countDown();
  }

  @Test
  void shutdownDrainsBoundedlyAndCountsWhatWasNotSent(CapturedOutput output) throws Exception {
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenAnswer(
            invocation -> {
              sending.countDown();
              never.await(); // a send stuck until the sender is interrupted
              return new CompletableFuture<>();
            });
    publisher = newPublisher(10, Duration.ofMillis(200));
    publisher.onPatientCreated(newEvent());
    assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
    PatientCreated queued = newEvent();
    publisher.onPatientCreated(queued);

    publisher.destroy();
    PatientCreated afterShutdown = newEvent();
    publisher.onPatientCreated(afterShutdown);

    // The queued event and the one arriving after shutdown were never sent; the interrupted
    // in-flight send fails like any other send.
    assertThat(failures(PatientCreatedPublisher.SHUTDOWN)).isEqualTo(2);
    await().atMost(WAIT).until(() -> failures(PatientCreatedPublisher.SEND_FAILED) == 1);
    assertFailureLogged(output, queued, "shutdown", null);
    assertFailureLogged(output, afterShutdown, "shutdown", null);
  }

  private PatientCreatedPublisher newPublisher(int queueCapacity, Duration drainTimeout) {
    return new PatientCreatedPublisher(
        kafkaTemplate,
        jsonMapper,
        new PatientCreatedTopicProperties(TOPIC, 3, 1),
        new PatientEventPublisherProperties(queueCapacity, drainTimeout),
        meterRegistry);
  }

  private static PatientCreated newEvent() {
    return new PatientCreated(
        UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-07T12:34:56.789Z"), "corr-42");
  }

  @SuppressWarnings("unchecked")
  private ProducerRecord<String, String> sentRecord() {
    ArgumentCaptor<ProducerRecord<String, String>> record =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate, timeout(WAIT.toMillis())).send(record.capture());
    return record.getValue();
  }

  private double failures(String reason) {
    return meterRegistry
        .get(PatientCreatedPublisher.FAILURE_COUNTER)
        .tag("eventType", "patient.created")
        .tag("reason", reason)
        .counter()
        .count();
  }

  private double totalFailures() {
    return meterRegistry.get(PatientCreatedPublisher.FAILURE_COUNTER).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private static void assertFailureLogged(
      CapturedOutput output,
      PatientCreated failed,
      String reason,
      Class<? extends Throwable> causeType) {
    assertThat(output.getAll())
        .contains(
            "Failed to publish patient.created eventId="
                + failed.eventId()
                + " patientId="
                + failed.patientId()
                + " reason="
                + reason)
        .doesNotContain(SECRET_DETAIL);
    if (causeType != null) {
      assertThat(output.getAll()).contains(causeType.getName());
    }
  }
}
