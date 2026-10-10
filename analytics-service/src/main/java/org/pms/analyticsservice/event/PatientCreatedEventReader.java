package org.pms.analyticsservice.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolerant reader for the {@code patient.created} v1 contract (PROJECT_BRIEF.md, Kafka Event
 * Contract): fields it does not know are ignored, but a missing or invalid required field, another
 * event type, or an unsupported version is rejected with {@link InvalidEventException}.
 */
@Component
public class PatientCreatedEventReader {

  static final int SUPPORTED_VERSION = 1;

  private static final List<String> STRING_FIELDS =
      List.of("eventId", "eventType", "occurredAt", "patientId");

  private final ObjectReader payloadReader;
  private final ObjectReader treeReader;

  public PatientCreatedEventReader(JsonMapper jsonMapper) {
    // Set here rather than relying on the shared mapper's default: fields added later within
    // version 1 must never make a record unreadable.
    this.payloadReader =
        jsonMapper
            .readerFor(Payload.class)
            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.treeReader = jsonMapper.reader();
  }

  public PatientCreatedEvent read(String json) {
    Payload payload = parse(json);
    if (!PatientCreatedEvent.TYPE.equals(payload.eventType())) {
      throw new InvalidEventException("eventType is missing or not patient.created");
    }
    if (payload.eventVersion() == null || payload.eventVersion() != SUPPORTED_VERSION) {
      throw new InvalidEventException("eventVersion is missing or not supported");
    }
    if (payload.eventId() == null || payload.occurredAt() == null || payload.patientId() == null) {
      throw new InvalidEventException("a required field is missing");
    }
    return new PatientCreatedEvent(payload.eventId(), payload.occurredAt(), payload.patientId());
  }

  /**
   * Best effort, for logging a record that could not be processed: its {@code eventId} when the
   * payload is JSON with a UUID there, otherwise empty. Only a parsed UUID is returned, never raw
   * payload text.
   */
  public Optional<UUID> eventIdOf(String json) {
    if (json == null) {
      return Optional.empty();
    }
    try {
      JsonNode eventId = treeReader.readTree(json).path("eventId");
      return eventId.isString()
          ? Optional.of(UUID.fromString(eventId.stringValue()))
          : Optional.empty();
    } catch (JacksonException | IllegalArgumentException notParseable) {
      // Expected for exactly the records being dead-lettered; there is simply no ID to report.
      return Optional.empty();
    }
  }

  private Payload parse(String json) {
    if (json == null) {
      throw new InvalidEventException("record has no payload");
    }
    try {
      JsonNode root = payloadReader.readTree(json);
      if (root == null || !root.isObject()) {
        throw new InvalidEventException("payload is not a JSON object");
      }
      requireJsonTypes(root);
      return payloadReader.readValue(root);
    } catch (JacksonException e) {
      // Not kept as the cause: parser messages can quote parts of the payload.
      throw new InvalidEventException("payload is not a valid patient.created JSON document");
    }
  }

  /**
   * Jackson would coerce {@code "1"} or {@code 1.9} to the integer 1 and read a number as an epoch
   * {@code occurredAt}. The contract has exact JSON types, so anything else is rejected here.
   */
  private static void requireJsonTypes(JsonNode root) {
    for (String field : STRING_FIELDS) {
      JsonNode value = root.path(field);
      if (!value.isMissingNode() && !value.isNull() && !value.isString()) {
        throw new InvalidEventException("a field has the wrong JSON type");
      }
    }
    JsonNode version = root.path("eventVersion");
    if (!version.isMissingNode() && !version.isNull() && !version.isIntegralNumber()) {
      throw new InvalidEventException("a field has the wrong JSON type");
    }
  }

  /** The wire shape. Typed fields make Jackson reject non-UUID IDs and non-ISO-8601 instants. */
  record Payload(
      UUID eventId, String eventType, Integer eventVersion, Instant occurredAt, UUID patientId) {}
}
