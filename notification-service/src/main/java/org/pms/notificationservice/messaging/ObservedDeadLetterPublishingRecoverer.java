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

/**
 * Publishes a record that could not be processed to this consumer's dead-letter topic, then makes
 * that visible: one ERROR line and one counter increment per dead-lettered record. A record is
 * never dropped silently: when the publication itself fails, that is logged and counted separately,
 * and the exception propagates so the error handler does not commit past the record.
 *
 * <p>The dead-letter record keeps the original value, key and headers (including the correlation
 * ID). Its partition is left unset, so the producer partitions it by the original key: records of
 * one patient stay together and in order, whatever the two topics' partition counts. Spring's
 * {@code kafka_dlt-*} headers record the source topic, partition, offset, consumer group and the
 * exception classes, but not the exception message or stack trace: those are free text that could
 * carry data, and anyone who can read the dead-letter topic would see them.
 *
 * <p>Delivery to the dead-letter topic is at least once: a crash after a successful publication but
 * before the source offset is committed redelivers the record, which then fails and is
 * dead-lettered again. Readers of the dead-letter topic should deduplicate by {@code eventId} or by
 * the {@code kafka_dlt-original-topic/partition/offset} headers.
 *
 * <p>Log lines hold coordinates and IDs only: never the payload or the exception message. The
 * correlation ID is read from the record itself; the record interceptor's MDC entry is not
 * guaranteed to still be set when the error handler recovers a record.
 */
public class ObservedDeadLetterPublishingRecoverer extends DeadLetterPublishingRecoverer {

  private static final Logger log =
      LoggerFactory.getLogger(ObservedDeadLetterPublishingRecoverer.class);

  private final String deadLetterTopic;
  private final PatientCreatedEventReader reader;
  private final Counter deadLettered;
  private final Counter publicationFailures;

  public ObservedDeadLetterPublishingRecoverer(
      KafkaOperations<?, ?> template,
      String deadLetterTopic,
      PatientCreatedEventReader reader,
      Counter deadLettered,
      Counter publicationFailures) {
    // Partition -1: unset, so the producer's partitioner chooses by key.
    super(template, (record, ex) -> new TopicPartition(deadLetterTopic, -1));
    excludeHeader(HeaderNames.HeadersToAdd.EX_MSG, HeaderNames.HeadersToAdd.EX_STACKTRACE);
    this.deadLetterTopic = deadLetterTopic;
    this.reader = reader;
    this.deadLettered = deadLettered;
    this.publicationFailures = publicationFailures;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    try {
      super.accept(record, consumer, exception);
    } catch (RuntimeException publicationFailure) {
      log.error(
          "Dead-letter publication to {} failed for record {}-{}@{} with {}; the record will be"
              + " retried (eventId={}, correlationId={})",
          deadLetterTopic,
          record.topic(),
          record.partition(),
          record.offset(),
          publicationFailure.getClass().getName(),
          eventId(record),
          correlationId(record));
      publicationFailures.increment();
      throw publicationFailure;
    }
    // Log first: whoever sees the counter move can rely on the line already being written.
    log.error(
        "Dead-lettered record {}-{}@{} to {} after {} (eventId={}, correlationId={})",
        record.topic(),
        record.partition(),
        record.offset(),
        deadLetterTopic,
        ListenerFailures.typeOf(exception),
        eventId(record),
        correlationId(record));
    deadLettered.increment();
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
