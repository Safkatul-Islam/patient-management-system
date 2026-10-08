package org.pms.analyticsservice.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * Publishes a failed record to the dead-letter topic (through {@code delegate}) and, once that
 * succeeded, makes it visible: the {@value #COUNTER} counter and one ERROR line with the record's
 * coordinates, the exception type, the event ID when readable and the correlation ID. Neither the
 * payload nor the exception message is logged.
 *
 * <p>If publishing fails, the delegate throws, nothing is counted, and the error handler keeps the
 * record for another attempt instead of dropping it.
 */
class DeadLetterRecoverer implements ConsumerAwareRecordRecoverer {

  static final String COUNTER = "pms.events.dead_lettered";

  private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

  private final ConsumerAwareRecordRecoverer delegate;
  private final PatientCreatedEventReader reader;
  private final Counter deadLettered;

  DeadLetterRecoverer(
      ConsumerAwareRecordRecoverer delegate,
      PatientCreatedEventReader reader,
      MeterRegistry meterRegistry,
      String consumer) {
    this.delegate = delegate;
    this.reader = reader;
    this.deadLettered =
        Counter.builder(COUNTER)
            .description("Records published to a dead-letter topic")
            .tag("consumer", consumer)
            .register(meterRegistry);
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    delegate.accept(record, consumer, exception);
    deadLettered.increment();
    logDeadLettered(record, exception);
  }

  private void logDeadLettered(ConsumerRecord<?, ?> record, Exception exception) {
    // Not relying on the record interceptor's MDC entry, which is scoped to the listener call:
    // the correlation ID is taken from the record itself (validated, never echoed when malformed).
    String previous = MDC.get(CorrelationIds.MDC_KEY);
    MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.resolve(correlationHeader(record)));
    try {
      log.error(
          "Dead-lettered record topic={} partition={} offset={} exceptionType={} eventId={}",
          record.topic(),
          record.partition(),
          record.offset(),
          rootFailure(exception).getClass().getName(),
          eventId(record));
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationIds.MDC_KEY);
      } else {
        MDC.put(CorrelationIds.MDC_KEY, previous);
      }
    }
  }

  private String eventId(ConsumerRecord<?, ?> record) {
    return record.value() instanceof String payload
        ? reader.eventIdOf(payload).map(Object::toString).orElse("unknown")
        : "unknown";
  }

  /** The listener's own exception, not the container's wrapper around it. */
  private static Throwable rootFailure(Exception exception) {
    return exception instanceof ListenerExecutionFailedException && exception.getCause() != null
        ? exception.getCause()
        : exception;
  }

  private static String correlationHeader(ConsumerRecord<?, ?> record) {
    Header header = record.headers().lastHeader(CorrelationIds.HEADER);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }
}
