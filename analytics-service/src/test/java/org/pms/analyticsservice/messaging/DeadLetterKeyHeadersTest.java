package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins that the configured dead-letter publisher drops the exception message and stack trace for a
 * key that failed to deserialize too: Spring Kafka gates the key and value variants of those
 * headers with the same {@code EX_MSG} and {@code EX_STACKTRACE} switches. Today's key deserializer
 * is a plain {@code StringDeserializer}, so this path is unreachable in the real container.
 */
class DeadLetterKeyHeadersTest {

  private static final String SECRET = "secret-key-detail-never-in-headers";

  @Test
  @DisplayName("A key that failed to deserialize is dead-lettered with its exception class only")
  @SuppressWarnings("unchecked")
  void keyExceptionTextIsExcluded() {
    KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);
    when(template.getProducerFactory()).thenReturn(producerFactory);
    when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
    AtomicReference<ProducerRecord<Object, Object>> published = new AtomicReference<>();
    when(template.send(any(ProducerRecord.class)))
        .thenAnswer(
            invocation -> {
              ProducerRecord<Object, Object> deadLetter = invocation.getArgument(0);
              published.set(deadLetter);
              return CompletableFuture.completedFuture(new SendResult<>(deadLetter, null));
            });
    CommonErrorHandler errorHandler =
        new KafkaErrorHandlingConfig()
            .patientCreatedErrorHandler(
                template,
                new PatientCreatedEventReader(JsonMapper.builder().build()),
                new SimpleMeterRegistry(),
                "patient.created.analytics-service.dlt",
                "analytics-service");

    byte[] badKey = "bad-key".getBytes(StandardCharsets.UTF_8);
    IllegalArgumentException keyFailure = new IllegalArgumentException(SECRET);
    RecordHeaders headers = new RecordHeaders();
    SerializationUtils.deserializationException(headers, badKey, keyFailure, true);
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(
            "patient.created",
            0,
            7L,
            ConsumerRecord.NO_TIMESTAMP,
            TimestampType.NO_TIMESTAMP_TYPE,
            ConsumerRecord.NULL_SIZE,
            ConsumerRecord.NULL_SIZE,
            null,
            "{}",
            headers,
            Optional.empty());

    errorHandler.handleOne(
        new DeserializationException(SECRET, badKey, true, keyFailure),
        record,
        mock(Consumer.class),
        mock(MessageListenerContainer.class));

    ProducerRecord<Object, Object> deadLetter = published.get();
    assertThat(deadLetter).as("dead letter published").isNotNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_FQCN))
        .as("key exception class is kept")
        .isNotNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_MESSAGE)).isNull();
    assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_KEY_EXCEPTION_STACKTRACE)).isNull();
    for (Header header : deadLetter.headers()) {
      if (header.key().startsWith("kafka_dlt")) {
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
            .as(header.key())
            .doesNotContain(SECRET);
      }
    }
  }
}
