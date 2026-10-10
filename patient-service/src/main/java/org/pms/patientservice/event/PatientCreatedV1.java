package org.pms.patientservice.event;

import java.util.UUID;

/**
 * Wire payload of {@code patient.created} version 1, exactly the fields of the contract in
 * PROJECT_BRIEF.md and {@code contracts/events/patient.created.v1.json}. No personal data: adding a
 * field here changes the contract.
 *
 * <p>{@code occurredAt} is pre-formatted as an ISO-8601 UTC instant so the wire format does not
 * depend on the JSON mapper's date settings.
 */
record PatientCreatedV1(
    UUID eventId, String eventType, int eventVersion, String occurredAt, UUID patientId) {

  static final String EVENT_TYPE = "patient.created";
  static final int EVENT_VERSION = 1;

  static PatientCreatedV1 from(PatientCreated event) {
    return new PatientCreatedV1(
        event.eventId(),
        EVENT_TYPE,
        EVENT_VERSION,
        event.occurredAt().toString(),
        event.patientId());
  }
}
