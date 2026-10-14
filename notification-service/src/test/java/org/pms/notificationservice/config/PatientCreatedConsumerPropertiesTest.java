package org.pms.notificationservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.notificationservice.config.PatientCreatedConsumerProperties.ConnectivityRetry;

class PatientCreatedConsumerPropertiesTest {

  private static final Duration SECOND = Duration.ofSeconds(1);

  @Test
  @DisplayName("The shipped retry settings are accepted")
  void validSettings() {
    assertThatCode(
            () -> properties(SECOND, retry(SECOND, Duration.ofSeconds(30), Duration.ofMinutes(15))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A zero retry interval is rejected: retries would run back to back")
  void zeroRetryInterval() {
    assertThatThrownBy(() -> properties(Duration.ZERO, defaultRetry()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retry-interval must be positive");
  }

  @Test
  @DisplayName("A zero initial connectivity interval is rejected: it would retry with no pause")
  void zeroInitialInterval() {
    assertThatThrownBy(() -> retry(Duration.ZERO, Duration.ofSeconds(30), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectivity-retry.initial-interval must be positive");
  }

  @Test
  @DisplayName("A zero max elapsed time is rejected")
  void zeroMaxElapsedTime() {
    assertThatThrownBy(() -> retry(SECOND, Duration.ofSeconds(30), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectivity-retry.max-elapsed-time must be positive");
  }

  @Test
  @DisplayName("A max interval below the initial interval is rejected")
  void maxBelowInitial() {
    assertThatThrownBy(
            () -> retry(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofMinutes(15)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectivity-retry.max-interval must not be below initial-interval");
  }

  @Test
  @DisplayName("The poll window is the configured max.poll.interval.ms, else Kafka's own default")
  void effectiveMaxPollInterval() {
    assertThat(MaxPollInterval.of(Map.of())).isEqualTo(Duration.ofMinutes(5));
    assertThat(MaxPollInterval.of(Map.of("max.poll.interval.ms", 120_000)))
        .isEqualTo(Duration.ofMinutes(2));
    assertThat(MaxPollInterval.of(Map.of("max.poll.interval.ms", "120000")))
        .isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  @DisplayName(
      "The longest pause must be at most half the poll window, whatever it is configured to")
  void maxIntervalWithinPollInterval() {
    ConnectivityRetry retry = retry(SECOND, Duration.ofSeconds(30), Duration.ofMinutes(15));

    assertThatCode(() -> retry.requireWithinPollInterval(Duration.ofMinutes(5)))
        .doesNotThrowAnyException();
    assertThatCode(() -> retry.requireWithinPollInterval(Duration.ofSeconds(60)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> retry.requireWithinPollInterval(Duration.ofSeconds(50)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be at most half of max.poll.interval.ms");
  }

  private static PatientCreatedConsumerProperties properties(
      Duration retryInterval, ConnectivityRetry retry) {
    return new PatientCreatedConsumerProperties(
        "patient.created", "patient.created.notification-service.dlt", 3, retryInterval, 2, retry);
  }

  private static ConnectivityRetry retry(
      Duration initialInterval, Duration maxInterval, Duration maxElapsedTime) {
    return new ConnectivityRetry(initialInterval, 2, maxInterval, maxElapsedTime);
  }

  private static ConnectivityRetry defaultRetry() {
    return retry(SECOND, Duration.ofSeconds(30), Duration.ofMinutes(15));
  }
}
