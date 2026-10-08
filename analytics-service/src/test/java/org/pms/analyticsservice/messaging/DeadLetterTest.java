package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.analyticsservice.event.InvalidEventException;
import org.pms.analyticsservice.support.AbstractConsumerIntegrationTest;
import org.pms.analyticsservice.support.ContainersConfig;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Failure handling in the real listener container. These tests also prove that Boot attached the
 * service's {@code CommonErrorHandler} bean to the container: without it there would be no retries
 * with back-off, no dead-letter topic and no counter.
 */
class DeadLetterTest extends AbstractConsumerIntegrationTest {

  @Test
  @DisplayName(
      "A failing effect rolls back, is tried 3 times, then dead-lettered; later events run")
  void failingEffectRetriedThenDeadLettered(CapturedOutput output) throws Exception {
    String key = UUID.randomUUID().toString();
    UUID failingId = UUID.randomUUID();
    LocalDate failingDay = unusedDay();
    String failing = payload(failingId, noonOf(failingDay));
    doThrow(new IllegalStateException("simulated effect failure"))
        .when(dailyRegistrations)
        .record(argThat(event -> failingId.equals(event.eventId())));
    double deadLetteredBefore = deadLetteredCount();

    send(null, key, failing, "anlt-fail-123");

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(failing);
    verify(dailyRegistrations, times(3))
        .record(argThat(event -> failingId.equals(event.eventId())));
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(IllegalStateException.class.getName());
    // Rolled back together: no idempotency row that would hide the event from a replay.
    assertThat(processedEvents.existsById(failingId)).isFalse();
    assertThat(registrationsOn(failingDay)).isZero();
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == deadLetteredBefore + 1);

    List<String> errorLines = deadLetterLines(output, failingId);
    assertThat(errorLines).hasSize(1);
    assertThat(errorLines.get(0))
        .contains("\"log\":{\"level\":\"ERROR\"")
        .doesNotContain("\"error\":") // no exception attached, so no message or stack trace
        .contains("topic=patient.created")
        .contains("exceptionType=java.lang.IllegalStateException")
        .contains("\"correlationId\":\"anlt-fail-123\"")
        .doesNotContain("simulated effect failure")
        .doesNotContain(failing)
        .doesNotContain("eventVersion");

    // The partition is not blocked: a later event for the same key is processed.
    UUID laterId = UUID.randomUUID();
    LocalDate laterDay = unusedDay();
    send(null, key, payload(laterId, noonOf(laterDay)), null);
    awaitProcessed(laterId);
    await().atMost(TIMEOUT).until(() -> registrationsOn(laterDay) == 1);
  }

  @Test
  @DisplayName("Malformed JSON and an unsupported version are dead-lettered without retries")
  void poisonRecordsNotRetried(CapturedOutput output) throws Exception {
    String malformed = "{\"eventId\": \"not closed " + UUID.randomUUID();
    UUID v2Id = UUID.randomUUID();
    String version2 = payload(v2Id, noonOf(unusedDay()), 2);
    double deadLetteredBefore = deadLetteredCount();

    send(null, UUID.randomUUID().toString(), malformed, null);
    send(null, UUID.randomUUID().toString(), version2, null);

    for (String poison : List.of(malformed, version2)) {
      assertThat(header(awaitDeadLetter(poison), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
          .isEqualTo(InvalidEventException.class.getName());
      verify(reader, times(1)).read(poison);
    }
    assertThat(processedEvents.existsById(v2Id)).isFalse();
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == deadLetteredBefore + 2);
    // The event ID is reported when the payload has one, even though the version is unsupported.
    assertThat(deadLetterLines(output, v2Id)).hasSize(1);
    assertThat(output.getAll()).doesNotContain("not closed");
  }

  @Test
  @DisplayName("The dead-letter topic has enough partitions and takes a record from partition 2")
  void deadLetterFromLastPartition() throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers()))) {
      Map<String, TopicDescription> topics =
          admin
              .describeTopics(List.of(ContainersConfig.SOURCE_TOPIC, DEAD_LETTER_TOPIC))
              .allTopicNames()
              .get(10, TimeUnit.SECONDS);
      int sourcePartitions = topics.get(ContainersConfig.SOURCE_TOPIC).partitions().size();
      assertThat(sourcePartitions).isEqualTo(ContainersConfig.SOURCE_PARTITIONS);
      assertThat(topics.get(DEAD_LETTER_TOPIC).partitions().size())
          .isGreaterThanOrEqualTo(sourcePartitions);
    }
    String poison = "not json " + UUID.randomUUID();

    send(2, "partition-two-key", poison, null);

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(poison);
    assertThat(deadLetter.topic()).isEqualTo(DEAD_LETTER_TOPIC);
    assertThat(deadLetter.key()).isEqualTo("partition-two-key");
    assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
        .isEqualTo(ContainersConfig.SOURCE_TOPIC);
    byte[] originalPartition =
        deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value();
    assertThat(ByteBuffer.wrap(originalPartition).getInt()).isEqualTo(2);
  }

  private static List<String> deadLetterLines(CapturedOutput output, UUID eventId) {
    return output
        .getAll()
        .lines()
        .filter(
            line -> line.contains("Dead-lettered record") && line.contains("eventId=" + eventId))
        .toList();
  }
}
