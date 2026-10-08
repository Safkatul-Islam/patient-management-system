package org.pms.notificationservice.messaging;

import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.util.backoff.BackOff;

/**
 * Chooses the back-off for a failed record by what failed. A database that cannot be reached is
 * waited out with the long, bounded connectivity back-off, so valid events are not dead-lettered by
 * an outage; any other failure gets the short default back-off. Records that can never succeed are
 * not retried at all (classified as not retryable on the error handler).
 */
public class PatientCreatedBackOffs
    implements BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> {

  private final BackOff connectivity;
  private final BackOff other;

  public PatientCreatedBackOffs(BackOff connectivity, BackOff other) {
    this.connectivity = connectivity;
    this.other = other;
  }

  @Override
  public BackOff apply(ConsumerRecord<?, ?> record, Exception exception) {
    return ListenerFailures.isConnectivityFailure(exception) ? connectivity : other;
  }
}
