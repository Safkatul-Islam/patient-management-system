package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pms.common.web.correlation.CorrelationIds;
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

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PatientCreated event =
      new PatientCreated(
          UUID.randomUUID(),
          UUID.randomUUID(),
          Instant.parse("2026-10-07T12:34:56.789Z"),
          "corr-42");

  private PatientCreatedPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher =
        new PatientCreatedPublisher(
            kafkaTemplate,
            jsonMapper,
            new PatientCreatedTopicProperties(TOPIC, 3, 1),
            meterRegistry);
  }

  @Test
  void sendsTheV1PayloadKeyedByPatientIdWithTheCorrelationHeader() {
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    publisher.publish(event);

    ProducerRecord<String, String> sent = sentRecord();
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

    publisher.publish(event);

    assertThat(failures()).isZero();
  }

  @Test
  void synchronousSendFailureIsLoggedAndCountedNotThrown(CapturedOutput output) {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenThrow(new KafkaException(SECRET_DETAIL));

    assertThatNoException().isThrownBy(() -> publisher.publish(event));

    assertThat(failures()).isEqualTo(1);
    assertFailureLogged(output, KafkaException.class);
  }

  @Test
  void asynchronousSendFailureIsLoggedAndCountedOnce(CapturedOutput output) {
    CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ack);

    publisher.publish(event);
    assertThat(failures()).isZero();

    ack.completeExceptionally(
        new KafkaProducerException(sentRecord(), SECRET_DETAIL, new TimeoutException()));

    assertThat(failures()).isEqualTo(1);
    assertFailureLogged(output, TimeoutException.class);
  }

  @SuppressWarnings("unchecked")
  private ProducerRecord<String, String> sentRecord() {
    ArgumentCaptor<ProducerRecord<String, String>> record =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(record.capture());
    return record.getValue();
  }

  private double failures() {
    return meterRegistry
        .get(PatientCreatedPublisher.FAILURE_COUNTER)
        .tag("eventType", "patient.created")
        .counter()
        .count();
  }

  private void assertFailureLogged(CapturedOutput output, Class<? extends Throwable> causeType) {
    assertThat(output.getAll())
        .contains(
            "Failed to publish patient.created eventId="
                + event.eventId()
                + " patientId="
                + event.patientId())
        .contains(causeType.getName())
        .doesNotContain(SECRET_DETAIL);
  }
}
