package org.pms.notificationservice.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service consumes {@code patient.created}. Validated at startup.
 *
 * @param topic the source topic
 * @param deadLetterTopic this consumer group's own dead-letter topic
 * @param deadLetterPartitions partitions of the dead-letter topic
 * @param retryInterval pause between attempts of a record that failed with an ordinary error
 * @param maxRetries retries after the first attempt before such a record is dead-lettered
 * @param connectivityRetry back-off for failures reaching this service's own database
 */
@ConfigurationProperties("pms.events.patient-created")
public record PatientCreatedConsumerProperties(
    String topic,
    String deadLetterTopic,
    int deadLetterPartitions,
    Duration retryInterval,
    long maxRetries,
    ConnectivityRetry connectivityRetry) {

  public PatientCreatedConsumerProperties {
    requireText(topic, "topic");
    requireText(deadLetterTopic, "dead-letter-topic");
    if (topic.equals(deadLetterTopic)) {
      throw new IllegalArgumentException("dead-letter-topic must differ from topic");
    }
    if (deadLetterPartitions < 1) {
      throw new IllegalArgumentException("dead-letter-partitions must be at least 1");
    }
    requirePositive(retryInterval, "retry-interval");
    if (maxRetries < 0) {
      throw new IllegalArgumentException("max-retries must not be negative");
    }
    Objects.requireNonNull(connectivityRetry, "connectivity-retry is required");
  }

  /**
   * Exponential back-off while the database is unreachable: a valid event must wait out an outage,
   * not be dead-lettered by it. Bounded, so a permanent outage still ends in the dead-letter topic.
   *
   * @param initialInterval pause after the first failed attempt
   * @param multiplier growth of the pause per attempt
   * @param maxInterval longest single pause; the consumer thread sleeps through it between polls,
   *     so it must stay at most half the consumer's effective {@code max.poll.interval.ms}, checked
   *     at startup by {@link #requireWithinPollInterval}
   * @param maxElapsedTime total pause time after which the record is dead-lettered
   */
  public record ConnectivityRetry(
      Duration initialInterval, double multiplier, Duration maxInterval, Duration maxElapsedTime) {

    public ConnectivityRetry {
      requirePositive(initialInterval, "connectivity-retry.initial-interval");
      requirePositive(maxElapsedTime, "connectivity-retry.max-elapsed-time");
      Objects.requireNonNull(maxInterval, "connectivity-retry.max-interval is required");
      if (maxInterval.compareTo(initialInterval) < 0) {
        throw new IllegalArgumentException(
            "connectivity-retry.max-interval must not be below initial-interval");
      }
      if (multiplier < 1) {
        throw new IllegalArgumentException("connectivity-retry.multiplier must be at least 1");
      }
    }

    /**
     * Fails unless the longest pause is at most half the consumer's {@code max.poll.interval.ms}:
     * the consumer thread sleeps through a pause, and must poll again before the broker evicts it.
     */
    public void requireWithinPollInterval(Duration maxPollInterval) {
      if (maxInterval.compareTo(maxPollInterval.dividedBy(2)) > 0) {
        throw new IllegalStateException(
            "connectivity-retry.max-interval ("
                + maxInterval
                + ") must be at most half of max.poll.interval.ms ("
                + maxPollInterval
                + ")");
      }
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
