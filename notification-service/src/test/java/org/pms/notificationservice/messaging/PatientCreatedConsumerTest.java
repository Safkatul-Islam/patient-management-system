package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/** The success path, duplicate delivery, and correlation-ID propagation into consumer logs. */
class PatientCreatedConsumerTest extends AbstractPatientCreatedIntegrationTest {

  private static final String LOG_LINE = "Recorded welcome notification for patient ";

  @Test
  @DisplayName("A valid event records one notification and marks the event processed")
  void validEventRecordsOneNotification() {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();

    send(patientId.toString(), event(eventId, patientId), "notif-happy-1");

    await().atMost(TIMEOUT).until(() -> notificationsFor(eventId) == 1);
    assertThat(processedEventsFor(eventId)).isEqualTo(1);
    var row =
        jdbcTemplate.queryForMap(
            "SELECT patient_id, kind, status FROM notifications WHERE event_id = ?", eventId);
    assertThat(row)
        .containsEntry("patient_id", patientId)
        .containsEntry("kind", "WELCOME")
        .containsEntry("status", "RECORDED");
  }

  @Test
  @DisplayName("The same event delivered twice has exactly one business effect")
  void duplicateDeliveryHasOneEffect() {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    String payload = event(eventId, patientId);

    send(patientId.toString(), payload, "notif-dup-1");
    send(patientId.toString(), payload, "notif-dup-1");
    // Same key, so same partition: once this later event is applied, both copies are done.
    UUID laterEventId = UUID.randomUUID();
    send(patientId.toString(), event(laterEventId, patientId), "notif-dup-2");

    await().atMost(TIMEOUT).until(() -> notificationsFor(laterEventId) == 1);
    verify(reader, times(2)).read(payload);
    verify(recorder, times(1)).record(argThat(event -> event.eventId().equals(eventId)));
    assertThat(notificationsFor(eventId)).isEqualTo(1);
    assertThat(processedEventsFor(eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName("The X-Correlation-Id header reaches the listener's log line (record interceptor)")
  void correlationIdFromHeaderIsInLog(CapturedOutput output) {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();

    send(patientId.toString(), event(eventId, patientId), "notif-corr-123");

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(linesContaining(output, LOG_LINE + patientId))
                    .singleElement()
                    .asString()
                    .contains("\"correlationId\":\"notif-corr-123\""));
    assertThat(output.getAll()).doesNotContain(PAYLOAD_MARKER);
  }

  @Test
  @DisplayName("A malformed X-Correlation-Id is replaced, never echoed into logs")
  void malformedCorrelationIdIsReplaced(CapturedOutput output) {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    String malformed = "bad correlation value <script>";

    send(patientId.toString(), event(eventId, patientId), malformed);

    await().atMost(TIMEOUT).until(() -> !linesContaining(output, LOG_LINE + patientId).isEmpty());
    List<String> lines = linesContaining(output, LOG_LINE + patientId);
    assertThat(lines)
        .singleElement()
        .asString()
        .containsPattern("\"correlationId\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
    assertThat(output.getAll()).doesNotContain("bad correlation value");
    verify(recorder, timeout(TIMEOUT.toMillis()))
        .record(argThat(event -> event.eventId().equals(eventId)));
  }
}
