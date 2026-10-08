package org.pms.analyticsservice.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * This service's own view of a validated {@code patient.created} v1 event: only the fields it uses.
 * Built by {@link PatientCreatedEventReader}, never by deserializing a producer class.
 */
public record PatientCreatedEvent(UUID eventId, Instant occurredAt, UUID patientId) {

  public static final String TYPE = "patient.created";

  /** The UTC calendar day the registration counts towards, whatever zone the service runs in. */
  public LocalDate registrationDay() {
    return LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
  }
}
