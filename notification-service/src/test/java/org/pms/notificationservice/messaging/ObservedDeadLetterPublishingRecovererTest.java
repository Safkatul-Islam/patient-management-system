package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.notificationservice.exception.InvalidEventException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/** A dead-letter publication that fails must be visible and must not count as dead-lettered. */
@ExtendWith(OutputCaptureExtension.class)
class ObservedDeadLetterPublishingRecovererTest {

  private static final String DLT = "patient.created.notification-service.dlt";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Counter deadLettered = registry.counter("dead_lettered");
  private final Counter publicationFailures = registry.counter("dead_letter.failures");

  @Test
  @DisplayName(
      "A failed DLT publish is logged and counted, rethrown, and not counted as dead-lettered")
  @SuppressWarnings("unchecked")
  void failedPublicationIsCountedAndRethrown(CapturedOutput output) {
    KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);
    when(template.getProducerFactory()).thenReturn(producerFactory);
    when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
    CompletableFuture<SendResult<Object, Object>> failed =
        CompletableFuture.failedFuture(new KafkaException("Simulated broker unavailable"));
    when(template.send(any(ProducerRecord.class))).thenReturn(failed);
    ObservedDeadLetterPublishingRecoverer recoverer =
        new ObservedDeadLetterPublishingRecoverer(
            template,
            DLT,
            new PatientCreatedEventReader(JsonMapper.builder().build()),
            deadLettered,
            publicationFailures);
    UUID eventId = UUID.randomUUID();
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(
            "patient.created", 1, 42L, "key", "{\"eventId\":\"" + eventId + "\",\"x\":1}");
    record.headers().add("X-Correlation-Id", "unit-corr-1".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () -> recoverer.accept(record, null, new InvalidEventException("Unsupported")))
        .isInstanceOf(RuntimeException.class);

    verify(template).send(any(ProducerRecord.class));
    assertThat(deadLettered.count()).isZero();
    assertThat(publicationFailures.count()).isEqualTo(1);
    assertThat(output.getAll())
        .contains("Dead-letter publication to " + DLT + " failed for record patient.created-1@42")
        .contains("eventId=" + eventId)
        .contains("correlationId=unit-corr-1")
        .doesNotContain("Dead-lettered record");
  }
}
