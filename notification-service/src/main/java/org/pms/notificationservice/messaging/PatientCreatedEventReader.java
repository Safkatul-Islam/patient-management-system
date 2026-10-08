package org.pms.notificationservice.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pms.notificationservice.exception.InvalidEventException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolerant reader for the {@code patient.created} contract: binds only the fields this service
 * needs, ignores any it does not know (fields added later within version 1), and rejects anything
 * that is not a complete version 1 event with an {@link InvalidEventException}.
 */
@Component
public class PatientCreatedEventReader {

  private static final String EVENT_ID = "eventId";

  private final JsonMapper jsonMapper;

  public PatientCreatedEventReader(JsonMapper jsonMapper) {
    // Set explicitly instead of inheriting whatever the shared mapper defaults to: ignoring
    // unknown fields is what makes this a tolerant reader, and an eventVersion of 1.5 must not be
    // truncated into a supported version.
    this.jsonMapper =
        jsonMapper
            .rebuild()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();
  }

  /** Reads a record value as a version 1 event, or throws {@link InvalidEventException}. */
  public PatientCreatedEvent read(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new InvalidEventException("Event payload is empty");
    }
    Payload event = parse(payload);
    if (!PatientCreatedEvent.TYPE.equals(event.eventType())) {
      throw new InvalidEventException("Event type is not " + PatientCreatedEvent.TYPE);
    }
    if (event.eventVersion() == null
        || event.eventVersion() != PatientCreatedEvent.SUPPORTED_VERSION) {
      throw new InvalidEventException("Event version is not supported");
    }
    requirePresent(event.eventId(), EVENT_ID);
    requirePresent(event.occurredAt(), "occurredAt");
    requirePresent(event.patientId(), "patientId");
    return new PatientCreatedEvent(event.eventId(), event.occurredAt(), event.patientId());
  }

  /**
   * Best-effort extraction of a well-formed event ID from any payload, for diagnostics on records
   * that failed. Never throws; empty when the payload has no valid UUID {@code eventId}.
   */
  public Optional<UUID> eventIdOf(String payload) {
    if (payload == null || payload.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode eventId = jsonMapper.readTree(payload).get(EVENT_ID);
      if (eventId == null || !eventId.isString()) {
        return Optional.empty();
      }
      return Optional.of(UUID.fromString(eventId.stringValue()));
    } catch (JacksonException | IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private Payload parse(String payload) {
    Payload event;
    try {
      event = jsonMapper.readValue(payload, Payload.class);
    } catch (JacksonException ex) {
      // Not chained: the parser's message can quote the payload (see InvalidEventException).
      throw new InvalidEventException("Event payload is not a valid patient.created JSON object");
    }
    if (event == null) {
      throw new InvalidEventException("Event payload is JSON null");
    }
    return event;
  }

  private static void requirePresent(Object value, String field) {
    if (value == null) {
      throw new InvalidEventException("Event field " + field + " is missing");
    }
  }

  /** The wire shape; every field nullable so absence is reported by name, not by the parser. */
  record Payload(
      UUID eventId, String eventType, Integer eventVersion, Instant occurredAt, UUID patientId) {}
}
