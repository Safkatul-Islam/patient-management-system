package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.notificationservice.exception.InvalidEventException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Failure handling inside the real listener container. These tests only pass if Boot attached this
 * service's {@code CommonErrorHandler} bean to the container: the container's default handler
 * neither retries with this back-off nor publishes to the dead-letter topic.
 */
class PatientCreatedFailureTest extends AbstractPatientCreatedIntegrationTest {

  private static final String DLT_LOG = "Dead-lettered record ";

  @Test
  @DisplayName(
      "A failing effect rolls back, is retried 3 times, then dead-lettered; later events flow")
  void failingEffectIsRetriedThenDeadLettered(CapturedOutput output) {
    UUID failingEventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    doAnswer(
            invocation -> {
              PatientCreatedEvent event = invocation.getArgument(0);
              if (event.eventId().equals(failingEventId)) {
                throw new IllegalStateException("Simulated notification store failure");
              }
              return invocation.callRealMethod();
            })
        .when(recorder)
        .record(any());
    double before = deadLetteredCount();
    String payload = event(failingEventId, patientId);

    send(patientId.toString(), payload, "notif-fail-1");

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(payload);
    // Once dead-lettered, no attempt is pending: 1 delivery + 2 retries.
    verify(recorder, times(3)).record(argThat(event -> event.eventId().equals(failingEventId)));
    // Each attempt's processed-event marker rolled back with the failed effect.
    assertThat(processedEventsFor(failingEventId)).isZero();
    assertThat(notificationsFor(failingEventId)).isZero();
    assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(properties.topic());
    assertThat(header(deadLetter, "X-Correlation-Id")).isEqualTo("notif-fail-1");
    assertNoExceptionText(deadLetter);

    // One WARN per failed attempt, the last one included; class names only, no message.
    String coordinates = sourceCoordinates(deadLetter);
    await()
        .atMost(TIMEOUT)
        .until(() -> linesContaining(output, "failed for record " + coordinates).size() == 3);
    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(
              linesContaining(output, "Attempt " + attempt + " failed for record " + coordinates))
          .singleElement()
          .asString()
          .contains("\"level\":\"WARN\"")
          .contains(
              "with java.lang.IllegalStateException"
                  + " rootCauseType=java.lang.IllegalStateException\"")
          .doesNotContain("Simulated notification store failure");
    }

    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == before + 1);
    await()
        .atMost(TIMEOUT)
        .until(() -> !linesContaining(output, DLT_LOG, failingEventId.toString()).isEmpty());
    assertThat(linesContaining(output, DLT_LOG, failingEventId.toString()))
        .singleElement()
        .asString()
        .contains("\"level\":\"ERROR\"")
        .contains(properties.topic() + "-")
        .contains("after java.lang.IllegalStateException")
        .contains("correlationId=notif-fail-1")
        .doesNotContain(patientId.toString())
        .doesNotContain("Simulated notification store failure");
    assertThat(output.getAll()).doesNotContain(PAYLOAD_MARKER);

    // Same key, so same partition: the partition is not stuck behind the failed record.
    UUID laterEventId = UUID.randomUUID();
    send(patientId.toString(), event(laterEventId, patientId), "notif-fail-2");
    await().atMost(TIMEOUT).until(() -> notificationsFor(laterEventId) == 1);
    assertThat(processedEventsFor(laterEventId)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "An effect that fails after the dedupe marker is written rolls both back; the redelivery"
          + " applies it once")
  void failedEffectRollsBackMarkerAndRedeliveryAppliesIt() {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    // The processor writes the marker first, then calls the recorder in the same transaction, so
    // this failure happens after the marker insert.
    doAnswer(
            invocation -> {
              PatientCreatedEvent event = invocation.getArgument(0);
              if (event.eventId().equals(eventId) && attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated failure after the marker insert");
              }
              return invocation.callRealMethod();
            })
        .when(recorder)
        .record(any());
    String payload = event(eventId, patientId);

    send(patientId.toString(), payload, "notif-redeliver-1");

    await().atMost(TIMEOUT).until(() -> notificationsFor(eventId) == 1);
    // Called twice: had attempt 1's marker survived, the redelivery would have been skipped as a
    // duplicate and the effect would never have been applied.
    verify(recorder, times(2)).record(argThat(event -> event.eventId().equals(eventId)));
    assertThat(processedEventsFor(eventId)).isEqualTo(1);
    assertThat(notificationsFor(eventId)).isEqualTo(1);
    assertThat(deadLettersWithValue(payload)).isEmpty();
  }

  @Test
  @DisplayName("Malformed JSON is dead-lettered on the first attempt, without retries")
  void malformedJsonIsDeadLetteredWithoutRetry(CapturedOutput output) {
    double before = deadLetteredCount();
    String payload = "{not json " + UUID.randomUUID() + " " + PAYLOAD_MARKER;

    send("malformed-key", payload, "notif-poison-1");

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(payload);
    verify(reader, times(1)).read(payload);
    // Spring records the listener's wrapper exception and its cause; ours is the cause.
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(InvalidEventException.class.getName());
    assertNoExceptionText(deadLetter);
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == before + 1);
    // Not retryable: its single failed attempt is the only one reported.
    assertThat(linesContaining(output, "failed for record " + sourceCoordinates(deadLetter)))
        .singleElement()
        .asString()
        .contains("Attempt 1 failed")
        .contains("with " + InvalidEventException.class.getName());
    await()
        .atMost(TIMEOUT)
        .until(() -> !linesContaining(output, DLT_LOG, "correlationId=notif-poison-1").isEmpty());
    assertThat(linesContaining(output, DLT_LOG, "correlationId=notif-poison-1"))
        .singleElement()
        .asString()
        .contains("eventId=unknown")
        .contains("after " + InvalidEventException.class.getName());
    assertThat(output.getAll()).doesNotContain(PAYLOAD_MARKER);
  }

  @Test
  @DisplayName("An unsupported eventVersion is dead-lettered on the first attempt, without retries")
  void unsupportedVersionIsDeadLetteredWithoutRetry(CapturedOutput output) {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    double before = deadLetteredCount();
    String payload = eventJson(eventId, patientId, 2);

    send(patientId.toString(), payload, "notif-poison-2");

    awaitDeadLetter(payload);
    verify(reader, times(1)).read(payload);
    verify(recorder, never()).record(argThat(event -> event.eventId().equals(eventId)));
    assertThat(processedEventsFor(eventId)).isZero();
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == before + 1);
    await()
        .atMost(TIMEOUT)
        .until(() -> !linesContaining(output, DLT_LOG, "eventId=" + eventId).isEmpty());
    assertThat(linesContaining(output, DLT_LOG, "eventId=" + eventId)).hasSize(1);
    assertThat(output.getAll()).doesNotContain(PAYLOAD_MARKER);
  }

  /** Exception classes and original-* headers are kept; free-text message and trace are not. */
  private static void assertNoExceptionText(ConsumerRecord<String, String> deadLetter) {
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotBlank();
    assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE)).isNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE)).isNull();
  }
}
