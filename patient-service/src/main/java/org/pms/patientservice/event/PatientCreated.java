package org.pms.patientservice.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * In-process event raised inside the transaction that creates a patient. {@link
 * PatientCreatedPublisher} turns it into a {@code patient.created} Kafka record once that
 * transaction has committed. It carries identifiers only, never patient data.
 *
 * @param correlationId the originating request's correlation ID, captured on the request thread
 */
public record PatientCreated(
    UUID eventId, UUID patientId, Instant occurredAt, String correlationId) {

  public PatientCreated {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(patientId, "patientId is required");
    Objects.requireNonNull(occurredAt, "occurredAt is required");
    Objects.requireNonNull(correlationId, "correlationId is required");
  }
}
