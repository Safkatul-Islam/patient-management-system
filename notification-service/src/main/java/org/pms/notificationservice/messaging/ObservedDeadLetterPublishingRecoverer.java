package org.pms.notificationservice.messaging;

import io.micrometer.core.instrument.Counter;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * Publishes a record that could not be processed to this consumer's dead-letter topic, then makes
 * that visible: one counter increment and one ERROR line per dead-lettered record. A record is
 * never dropped silently.
 *
 * <p>The record keeps its source partition number on the dead-letter topic, which therefore needs
 * at least as many partitions as the source. The original value, key and headers (including the
 * correlation ID) are carried over unchanged, with the failure recorded in Spring's {@code
 * kafka_dlt-*} headers.
 *
 * <p>The log line holds coordinates and IDs only: never the payload or the exception message. The
 * correlation ID is read from the record itself, because the record interceptor has already cleared
 * the MDC by the time the error handler recovers a record.
 */
public class ObservedDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer {

  private static final Logger log =
      LoggerFactory.getLogger(ObservedDeadLetterPublishingRecoverer.class);

  private final String deadLetterTopic;
  private final PatientCreatedEventReader reader;
  private final Counter deadLettered;

  public ObservedDeadLetterPublishingRecoverer(
      KafkaOperations<?, ?> template,
      String deadLetterTopic,
      PatientCreatedEventReader reader,
      Counter deadLettered) {
    super(template, (record, ex) -> new TopicPartition(deadLetterTopic, record.partition()));
    this.deadLetterTopic = deadLetterTopic;
    this.reader = reader;
    this.deadLettered = deadLettered;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    // Throws when the publication fails; the record then counts as not recovered and is retried.
    super.accept(record, consumer, exception);
    deadLettered.increment();
    log.error(
        "Dead-lettered record {}-{}@{} to {} after {} (eventId={}, correlationId={})",
        record.topic(),
        record.partition(),
        record.offset(),
        deadLetterTopic,
        failureType(exception),
        eventId(record),
        correlationId(record));
  }

  private static String failureType(Exception exception) {
    Throwable failure = exception;
    if (failure instanceof ListenerExecutionFailedException && failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure.getClass().getName();
  }

  private String eventId(ConsumerRecord<?, ?> record) {
    if (record.value() instanceof String payload) {
      return reader.eventIdOf(payload).map(Object::toString).orElse("unknown");
    }
    return "unknown";
  }

  /**
   * Same rule as the record interceptor: a missing or malformed value is replaced, never logged.
   */
  private static String correlationId(ConsumerRecord<?, ?> record) {
    Header header = record.headers().lastHeader(CorrelationIds.HEADER);
    String value =
        header == null || header.value() == null
            ? null
            : new String(header.value(), StandardCharsets.UTF_8);
    return CorrelationIds.resolve(value);
  }
}
