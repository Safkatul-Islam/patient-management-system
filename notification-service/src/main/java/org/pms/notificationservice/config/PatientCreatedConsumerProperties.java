package org.pms.notificationservice.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service consumes {@code patient.created}. Validated at startup.
 *
 * @param topic the source topic
 * @param deadLetterTopic this consumer group's own dead-letter topic
 * @param deadLetterPartitions partitions of the dead-letter topic; at least the source topic's, so
 *     a dead-lettered record keeps its source partition number
 * @param retryInterval pause between attempts of a record that failed with a retryable error
 * @param maxRetries retries after the first attempt before the record is dead-lettered
 */
@ConfigurationProperties("pms.events.patient-created")
public record PatientCreatedConsumerProperties(
    String topic,
    String deadLetterTopic,
    int deadLetterPartitions,
    Duration retryInterval,
    long maxRetries) {

  public PatientCreatedConsumerProperties {
    requireText(topic, "topic");
    requireText(deadLetterTopic, "dead-letter-topic");
    if (topic.equals(deadLetterTopic)) {
      throw new IllegalArgumentException("dead-letter-topic must differ from topic");
    }
    if (deadLetterPartitions < 1) {
      throw new IllegalArgumentException("dead-letter-partitions must be at least 1");
    }
    Objects.requireNonNull(retryInterval, "retry-interval is required");
    if (retryInterval.isNegative()) {
      throw new IllegalArgumentException("retry-interval must not be negative");
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("max-retries must not be negative");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
