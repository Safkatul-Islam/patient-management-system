package org.pms.notificationservice.config;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;

/** The consumer's effective {@code max.poll.interval.ms}: as configured, else Kafka's default. */
final class MaxPollInterval {

  private MaxPollInterval() {}

  static Duration of(Map<String, Object> consumerConfiguration) {
    Object configured = consumerConfiguration.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
    Object value =
        configured != null
            ? configured
            : ConsumerConfig.configDef()
                .defaultValues()
                .get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
    long millis =
        value instanceof Number number
            ? number.longValue()
            : Long.parseLong(value.toString().trim());
    return Duration.ofMillis(millis);
  }
}
