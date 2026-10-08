package org.pms.analyticsservice.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PatientCreatedEventTest {

  @Test
  @DisplayName("Counts towards the UTC calendar day of occurredAt")
  void bucketsByUtcDay() {
    assertThat(eventAt("2026-10-07T23:30:00Z").registrationDay())
        .isEqualTo(LocalDate.of(2026, 10, 7));
    assertThat(eventAt("2026-10-08T00:30:00Z").registrationDay())
        .isEqualTo(LocalDate.of(2026, 10, 8));
  }

  private static PatientCreatedEvent eventAt(String occurredAt) {
    return new PatientCreatedEvent(UUID.randomUUID(), Instant.parse(occurredAt), UUID.randomUUID());
  }
}
