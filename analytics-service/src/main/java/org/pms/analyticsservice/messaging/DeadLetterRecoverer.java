package org.pms.analyticsservice.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

/**
 * Publishes a failed record to the dead-letter topic (through {@code delegate}) and makes the
 * outcome visible. Neither the payload nor any exception message is logged.
 *
 * <ul>
 *   <li>Published: one ERROR line with the record's coordinates, the exception type, the event ID
 *       when readable and the correlation ID, then the {@value #DEAD_LETTERED} counter.
 *   <li>Publish failed: one ERROR line and the {@value #PUBLISH_FAILURES} counter, and the failure
 *       is rethrown so the error handler keeps the record and tries again instead of skipping it.
 * </ul>
 *
 * <p>Delivery to the dead-letter topic is at least once: after a crash between the publish and the
 * consumer's offset commit, the record is processed again and can be dead-lettered twice. Readers
 * of the dead-letter topic should deduplicate by {@code eventId} or by the {@code
 * kafka_dlt-original-topic/partition/offset} headers.
 */
class DeadLetterRecoverer implements ConsumerAwareRecordRecoverer {

  static final String DEAD_LETTERED = "pms.events.dead_lettered";
  static final String PUBLISH_FAILURES = "pms.events.dead_letter.failures";

  private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

  private final ConsumerAwareRecordRecoverer delegate;
  private final PatientCreatedEventReader reader;
  private final Counter deadLettered;
  private final Counter publishFailures;

  DeadLetterRecoverer(
      ConsumerAwareRecordRecoverer delegate,
      PatientCreatedEventReader reader,
      MeterRegistry meterRegistry,
      String consumer) {
    this.delegate = delegate;
    this.reader = reader;
    this.deadLettered =
        Counter.builder(DEAD_LETTERED)
            .description("Records published to a dead-letter topic")
            .tag("consumer", consumer)
            .register(meterRegistry);
    this.publishFailures =
        Counter.builder(PUBLISH_FAILURES)
            .description("Failed attempts to publish a record to a dead-letter topic")
            .tag("consumer", consumer)
            .register(meterRegistry);
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    try {
      delegate.accept(record, consumer, exception);
    } catch (RuntimeException publishFailure) {
      publishFailures.increment();
      ListenerFailures.withCorrelationId(
          record,
          () ->
              log.error(
                  "Dead-letter publish failed, record will be retried topic={} partition={}"
                      + " offset={} failureType={}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  publishFailure.getClass().getName()));
      throw publishFailure;
    }
    try {
      logDeadLettered(record, exception);
    } catch (RuntimeException loggingFailure) {
      // Deliberately not propagated: the record is already on the dead-letter topic, and an
      // exception here would make the error handler publish it a second time. With logging
      // itself failing there is nowhere left to report this.
    }
    deadLettered.increment();
  }

  private void logDeadLettered(ConsumerRecord<?, ?> record, Exception exception) {
    String failureType = ListenerFailures.listenerFailure(exception).getClass().getName();
    String eventId = eventId(record);
    ListenerFailures.withCorrelationId(
        record,
        () ->
            log.error(
                "Dead-lettered record topic={} partition={} offset={} exceptionType={} eventId={}",
                record.topic(),
                record.partition(),
                record.offset(),
                failureType,
                eventId));
  }

  private String eventId(ConsumerRecord<?, ?> record) {
    return record.value() instanceof String payload
        ? reader.eventIdOf(payload).map(Object::toString).orElse("unknown")
        : "unknown";
  }
}
