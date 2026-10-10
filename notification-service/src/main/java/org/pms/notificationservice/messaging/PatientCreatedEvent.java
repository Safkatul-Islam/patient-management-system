package org.pms.notificationservice.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * This service's own view of a {@code patient.created} version 1 event, as read by {@link
 * PatientCreatedEventReader}. Holds only the fields the notification effect needs; every one of
 * them is required by the contract. Type and version are checked by the reader, not carried.
 */
public record PatientCreatedEvent(UUID eventId, Instant occurredAt, UUID patientId) {

  public static final String TYPE = "patient.created";
  public static final int SUPPORTED_VERSION = 1;

  public PatientCreatedEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(patientId, "patientId");
  }
}
