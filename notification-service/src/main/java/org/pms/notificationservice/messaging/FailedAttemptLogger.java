package org.pms.notificationservice.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

/**
 * One WARN line per failed delivery attempt, so retries (including a long wait for the database)
 * are visible before a record is either processed or dead-lettered. Coordinates, attempt number,
 * the exception class and its root cause's class only: never the payload or any exception message.
 */
public class FailedAttemptLogger implements RetryListener {

  private static final Logger log = LoggerFactory.getLogger(FailedAttemptLogger.class);

  @Override
  public void failedDelivery(
      ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
    log.warn(
        "Attempt {} failed for record {}-{}@{} with {} rootCauseType={}",
        deliveryAttempt,
        record.topic(),
        record.partition(),
        record.offset(),
        ListenerFailures.typeOf(exception),
        ListenerFailures.rootCauseTypeOf(exception));
  }
}
