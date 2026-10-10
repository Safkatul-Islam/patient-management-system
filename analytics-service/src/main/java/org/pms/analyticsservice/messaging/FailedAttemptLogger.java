package org.pms.analyticsservice.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

/**
 * One WARN line per failed delivery attempt; Spring Kafka itself logs nothing while it retries.
 * Only the record's coordinates, the attempt number and exception class names are logged, never a
 * message or the payload.
 */
class FailedAttemptLogger implements RetryListener {

  private static final Logger log = LoggerFactory.getLogger(FailedAttemptLogger.class);

  @Override
  public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
    Throwable failure = ListenerFailures.listenerFailure(exception);
    ListenerFailures.withCorrelationId(
        record,
        () ->
            log.warn(
                "Delivery attempt failed topic={} partition={} offset={} attempt={}"
                    + " exceptionType={} rootCauseType={}",
                record.topic(),
                record.partition(),
                record.offset(),
                attempt,
                failure.getClass().getName(),
                ListenerFailures.rootCause(failure).getClass().getName()));
  }
}
