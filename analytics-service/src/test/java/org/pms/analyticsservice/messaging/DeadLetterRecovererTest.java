package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class DeadLetterRecovererTest {

  private static final String PAYLOAD =
      "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"patient.created\"}";

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ConsumerRecord<String, String> record =
      new ConsumerRecord<>("patient.created", 1, 42L, "key", PAYLOAD);

  @Test
  @DisplayName(
      "A failed dead-letter publish is counted, logged and rethrown, never counted as done")
  void publishFailure(CapturedOutput output) {
    DeadLetterRecoverer recoverer =
        recoverer(
            (rec, consumer, ex) -> {
              throw new KafkaException("broker unreachable");
            },
            new PatientCreatedEventReader(JsonMapper.builder().build()));

    assertThatThrownBy(() -> recoverer.accept(record, null, new IllegalStateException("boom")))
        .isInstanceOf(KafkaException.class);

    assertThat(count(DeadLetterRecoverer.PUBLISH_FAILURES)).isEqualTo(1);
    assertThat(count(DeadLetterRecoverer.DEAD_LETTERED)).isZero();
    assertThat(output.getAll())
        .contains("Dead-letter publish failed")
        .contains("offset=42")
        .contains("failureType=" + KafkaException.class.getName())
        .doesNotContain("Dead-lettered record")
        .doesNotContain("broker unreachable")
        .doesNotContain(PAYLOAD);
  }

  @Test
  @DisplayName("A failure while logging a published dead letter does not trigger a second publish")
  void loggingFailureDoesNotRepublish() {
    AtomicInteger publishes = new AtomicInteger();
    PatientCreatedEventReader failingReader =
        new PatientCreatedEventReader(JsonMapper.builder().build()) {
          @Override
          public Optional<UUID> eventIdOf(String json) {
            throw new IllegalStateException("logging path failed");
          }
        };
    DeadLetterRecoverer recoverer =
        recoverer((rec, consumer, ex) -> publishes.incrementAndGet(), failingReader);

    assertThatCode(() -> recoverer.accept(record, null, new IllegalStateException("boom")))
        .doesNotThrowAnyException();

    assertThat(publishes).hasValue(1);
    assertThat(count(DeadLetterRecoverer.DEAD_LETTERED)).isEqualTo(1);
    assertThat(count(DeadLetterRecoverer.PUBLISH_FAILURES)).isZero();
  }

  private DeadLetterRecoverer recoverer(
      ConsumerAwareRecordRecoverer delegate, PatientCreatedEventReader reader) {
    return new DeadLetterRecoverer(delegate, reader, meterRegistry, "analytics-service");
  }

  private double count(String name) {
    return meterRegistry.get(name).tag("consumer", "analytics-service").counter().count();
  }
}
