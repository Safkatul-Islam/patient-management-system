package org.pms.analyticsservice.messaging;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Helpers shared by the error handler's back-off choice, retry logging and dead-lettering. */
final class ListenerFailures {

  // Bounds the cause-chain walk in case an exception's causes form a cycle.
  private static final int MAX_CAUSE_DEPTH = 32;

  private ListenerFailures() {}

  /**
   * True when the database could not be reached or reported a transient condition (Spring's
   * data-access hierarchy anywhere in the cause chain). Such a failure says nothing about the
   * record, so it must not be dead-lettered after a few quick attempts.
   */
  static boolean isConnectivityFailure(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof TransientDataAccessException
          || current instanceof DataAccessResourceFailureException
          || current instanceof CannotCreateTransactionException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /** The listener's own exception, not the container's wrapper around it. */
  static Throwable listenerFailure(Exception exception) {
    return exception instanceof ListenerExecutionFailedException && exception.getCause() != null
        ? exception.getCause()
        : exception;
  }

  static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
      current = current.getCause();
    }
    return current;
  }

  /**
   * Runs {@code logStatement} with the record's correlation ID in the MDC. Error-handler callbacks
   * cannot rely on the record interceptor's MDC entry, which is scoped to the listener call, so the
   * ID is taken from the record header (validated, never echoed when malformed).
   */
  static void withCorrelationId(ConsumerRecord<?, ?> record, Runnable logStatement) {
    String previous = MDC.get(CorrelationIds.MDC_KEY);
    MDC.put(CorrelationIds.MDC_KEY, CorrelationIds.resolve(correlationHeader(record)));
    try {
      logStatement.run();
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationIds.MDC_KEY);
      } else {
        MDC.put(CorrelationIds.MDC_KEY, previous);
      }
    }
  }

  private static String correlationHeader(ConsumerRecord<?, ?> record) {
    Header header = record.headers().lastHeader(CorrelationIds.HEADER);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }
}
