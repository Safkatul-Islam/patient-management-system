package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.analyticsservice.model.ProcessedEvent;
import org.pms.analyticsservice.support.AbstractConsumerIntegrationTest;
import org.springframework.boot.test.system.CapturedOutput;

class PatientCreatedConsumerTest extends AbstractConsumerIntegrationTest {

  @Test
  @DisplayName("A valid event adds exactly one registration to its day and is recorded once")
  void validEventCounted() throws Exception {
    UUID eventId = UUID.randomUUID();
    LocalDate day = unusedDay();

    send(null, UUID.randomUUID().toString(), payload(eventId, noonOf(day)), null);

    awaitProcessed(eventId);
    await().atMost(TIMEOUT).until(() -> registrationsOn(day) == 1);
    assertThat(processedEvents.findById(eventId))
        .get()
        .extracting(ProcessedEvent::getEventType)
        .isEqualTo("patient.created");
  }

  @Test
  @DisplayName("The same event delivered twice has one business effect")
  void duplicateDeliveryCountedOnce() throws Exception {
    String key = UUID.randomUUID().toString();
    UUID eventId = UUID.randomUUID();
    LocalDate day = unusedDay();
    String duplicated = payload(eventId, noonOf(day));
    UUID sentinelId = UUID.randomUUID();

    send(null, key, duplicated, null);
    send(null, key, duplicated, null);
    // Same key, so same partition: once the later sentinel is processed, both copies are done.
    send(null, key, payload(sentinelId, noonOf(unusedDay())), null);

    awaitProcessed(sentinelId);
    verify(reader, times(2)).read(duplicated);
    assertThat(registrationsOn(day)).isEqualTo(1);
    assertThat(processedEvents.existsById(eventId)).isTrue();
  }

  @Test
  @DisplayName("Log lines carry the record's correlation ID; a malformed one is replaced")
  void correlationIdFromHeader(CapturedOutput output) throws Exception {
    UUID correlatedId = UUID.randomUUID();
    UUID malformedId = UUID.randomUUID();
    String malformed = "anlt bad\r\ncorr";

    send(
        null,
        UUID.randomUUID().toString(),
        payload(correlatedId, noonOf(unusedDay())),
        "anlt-corr-456");
    send(null, UUID.randomUUID().toString(), payload(malformedId, noonOf(unusedDay())), malformed);

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(countedLines(output, correlatedId))
                  .singleElement()
                  .asString()
                  .contains("\"correlationId\":\"anlt-corr-456\"");
              assertThat(countedLines(output, malformedId))
                  .singleElement()
                  .asString()
                  .containsPattern("\"correlationId\":\"[0-9a-f-]{36}\"");
            });
    assertThat(output.getAll()).doesNotContain("anlt bad");
  }

  private static List<String> countedLines(CapturedOutput output, UUID eventId) {
    return output
        .getAll()
        .lines()
        .filter(line -> line.contains("Counted patient registration eventId=" + eventId))
        .toList();
  }
}
