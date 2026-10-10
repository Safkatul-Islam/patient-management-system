package org.pms.notificationservice.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.pms.notificationservice.exception.InvalidEventException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tolerant but strict reader for the {@code patient.created} contract. Tolerant: it reads only the
 * fields it needs from the JSON tree, so fields added later within version 1 are ignored by
 * construction. Strict: each field it does read must have exactly the contract's JSON type (no
 * coercion of {@code "1"} into a version or of a number into a timestamp), and anything that is not
 * a complete version 1 event is rejected with an {@link InvalidEventException}.
 */
@Component
public class PatientCreatedEventReader {

  private static final String EVENT_ID = "eventId";

  // Canonical 8-4-4-4-12 form only; UUID.fromString alone also accepts shortened groups.
  private static final Pattern CANONICAL_UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final JsonMapper jsonMapper;

  public PatientCreatedEventReader(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /** Reads a record value as a version 1 event, or throws {@link InvalidEventException}. */
  public PatientCreatedEvent read(String payload) {
    JsonNode event = parseObject(payload);
    JsonNode type = event.get("eventType");
    if (type == null || !type.isString() || !PatientCreatedEvent.TYPE.equals(type.stringValue())) {
      throw new InvalidEventException("Event type is not " + PatientCreatedEvent.TYPE);
    }
    JsonNode version = event.get("eventVersion");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToInt()
        || version.intValue() != PatientCreatedEvent.SUPPORTED_VERSION) {
      throw new InvalidEventException("Event version is not supported");
    }
    return new PatientCreatedEvent(
        uuid(event, EVENT_ID), instant(event, "occurredAt"), uuid(event, "patientId"));
  }

  /**
   * Best-effort extraction of a well-formed event ID from any payload, for diagnostics on records
   * that failed. Never throws; empty when the payload has no valid UUID {@code eventId}.
   */
  public Optional<UUID> eventIdOf(String payload) {
    try {
      return Optional.of(uuid(parseObject(payload), EVENT_ID));
    } catch (InvalidEventException ex) {
      return Optional.empty();
    }
  }

  private JsonNode parseObject(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new InvalidEventException("Event payload is empty");
    }
    JsonNode tree;
    try {
      tree = jsonMapper.readTree(payload);
    } catch (JacksonException ex) {
      // Not chained: the parser's message can quote the payload (see InvalidEventException).
      throw new InvalidEventException("Event payload is not valid JSON");
    }
    if (tree == null || !tree.isObject()) {
      throw new InvalidEventException("Event payload is not a JSON object");
    }
    return tree;
  }

  private static UUID uuid(JsonNode event, String field) {
    String text = requiredString(event, field);
    if (!CANONICAL_UUID.matcher(text).matches()) {
      throw new InvalidEventException("Event field " + field + " is not a UUID");
    }
    return UUID.fromString(text);
  }

  private static Instant instant(JsonNode event, String field) {
    String text = requiredString(event, field);
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ex) {
      throw new InvalidEventException("Event field " + field + " is not an ISO-8601 instant");
    }
  }

  private static String requiredString(JsonNode event, String field) {
    JsonNode value = event.get(field);
    if (value == null || value.isNull()) {
      throw new InvalidEventException("Event field " + field + " is missing");
    }
    if (!value.isString()) {
      throw new InvalidEventException("Event field " + field + " is not a JSON string");
    }
    return value.stringValue();
  }
}
