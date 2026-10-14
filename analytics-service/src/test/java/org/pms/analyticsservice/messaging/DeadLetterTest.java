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
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
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

    RecordMetadata sent = send(null, key, failing, "anlt-fail-123");

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(failing);
    verify(dailyRegistrations, times(3))
        .record(argThat(event -> failingId.equals(event.eventId())));
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(IllegalStateException.class.getName());
    // Rolled back together: no idempotency row that would hide the event from a replay.
    assertThat(isProcessed(failingId)).isFalse();
    assertThat(registrationsOn(failingDay)).isZero();
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == deadLetteredBefore + 1);

    List<String> errorLines = linesWith(output, "Dead-lettered record", "eventId=" + failingId);
    assertThat(errorLines).hasSize(1);
    assertThat(errorLines.get(0))
        .contains("\"log\":{\"level\":\"ERROR\"")
        .doesNotContain("\"error\":") // no exception attached, so no message or stack trace
        .contains("topic=patient.created")
        .contains("exceptionType=java.lang.IllegalStateException")
        .contains("\"correlationId\":\"anlt-fail-123\"")
        .doesNotContain("simulated effect failure")
        .doesNotContain("eventVersion");
    List<String> attemptLines =
        linesWith(
            output,
            "Delivery attempt failed",
            "partition=" + sent.partition() + " offset=" + sent.offset() + " ");
    assertThat(attemptLines)
        .hasSize(3)
        .allSatisfy(
            line ->
                assertThat(line)
                    .contains("\"log\":{\"level\":\"WARN\"")
                    .contains("exceptionType=java.lang.IllegalStateException")
                    .contains("\"correlationId\":\"anlt-fail-123\"")
                    .doesNotContain("simulated effect failure")
                    .doesNotContain("\"error\":"));

    // The partition is not blocked: a later event for the same key is processed.
    UUID laterId = UUID.randomUUID();
    LocalDate laterDay = unusedDay();
    send(null, key, payload(laterId, noonOf(laterDay)), null);
    awaitProcessed(laterId);
    await().atMost(TIMEOUT).until(() -> registrationsOn(laterDay) == 1);
  }

  @Test
  @DisplayName(
      "An effect that fails after the dedupe marker is written rolls both back; the redelivery"
          + " applies it once")
  void failedEffectRollsBackMarkerAndRedeliveryAppliesIt() throws Exception {
    UUID eventId = UUID.randomUUID();
    LocalDate day = unusedDay();
    String event = payload(eventId, noonOf(day));
    // The handler writes the marker first, then records the registration in the same
    // transaction, so this failure happens after the marker insert.
    doThrow(new IllegalStateException("Simulated failure after the marker insert"))
        .doCallRealMethod()
        .when(dailyRegistrations)
        .record(argThat(recorded -> eventId.equals(recorded.eventId())));

    send(null, UUID.randomUUID().toString(), event, "anlt-redeliver-1");

    awaitProcessed(eventId);
    await().atMost(TIMEOUT).until(() -> registrationsOn(day) == 1);
    // Called twice: had attempt 1's marker survived, the redelivery would have been skipped as a
    // duplicate and the registration would never have been counted.
    verify(dailyRegistrations, times(2))
        .record(argThat(recorded -> eventId.equals(recorded.eventId())));
    assertThat(registrationsOn(day)).isEqualTo(1);
    assertThat(deadLettersWithValue(event)).isEmpty();
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
    }
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == deadLetteredBefore + 2);
    for (String poison : List.of(malformed, version2)) {
      verify(reader, times(1)).read(poison);
    }
    assertThat(isProcessed(v2Id)).isFalse();
    // The event ID is reported when the payload has one, even though the version is unsupported.
    assertThat(linesWith(output, "Dead-lettered record", "eventId=" + v2Id)).hasSize(1);
    assertThat(output.getAll()).doesNotContain("not closed");
  }

  @Test
  @DisplayName("Dead letters are routed by key, not source partition, and carry no exception text")
  void deadLettersRoutedByKey() throws Exception {
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
    String key = "same-key-" + UUID.randomUUID();
    String fromPartition0 = "not json " + UUID.randomUUID();
    String fromPartition2 = "not json " + UUID.randomUUID();
    double deadLetteredBefore = deadLetteredCount();

    send(0, key, fromPartition0, "anlt-route-0");
    send(2, key, fromPartition2, "anlt-route-2");

    ConsumerRecord<String, String> first = awaitDeadLetter(fromPartition0);
    ConsumerRecord<String, String> second = awaitDeadLetter(fromPartition2);
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == deadLetteredBefore + 2);
    // Same-partition routing would have put these on DLT partitions 0 and 2.
    assertThat(second.partition()).isEqualTo(first.partition());
    assertThat(originalPartition(first)).isZero();
    assertThat(originalPartition(second)).isEqualTo(2);
    assertThat(first.key()).isEqualTo(key);
    assertThat(second.key()).isEqualTo(key);
    assertThat(header(first, "X-Correlation-Id")).isEqualTo("anlt-route-0");
    assertThat(header(second, "X-Correlation-Id")).isEqualTo("anlt-route-2");
    assertThat(header(first, KafkaHeaders.DLT_ORIGINAL_TOPIC))
        .isEqualTo(ContainersConfig.SOURCE_TOPIC);

    List<String> headerNames = headerNames(first);
    assertThat(headerNames)
        .contains(KafkaHeaders.DLT_EXCEPTION_FQCN, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)
        .doesNotContain(
            KafkaHeaders.DLT_EXCEPTION_MESSAGE,
            KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
            KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE,
            KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE)
        .noneMatch(name -> name.contains("message") || name.contains("stacktrace"));
  }

  private static int originalPartition(ConsumerRecord<?, ?> record) {
    return ByteBuffer.wrap(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value())
        .getInt();
  }

  private static List<String> headerNames(ConsumerRecord<?, ?> record) {
    return StreamSupport.stream(record.headers().spliterator(), false).map(Header::key).toList();
  }

  private static List<String> linesWith(CapturedOutput output, String text, String marker) {
    return output
        .getAll()
        .lines()
        .filter(line -> line.contains(text) && line.contains(marker))
        .toList();
  }
}
