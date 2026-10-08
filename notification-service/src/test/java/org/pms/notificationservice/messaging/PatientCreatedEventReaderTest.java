package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pms.notificationservice.exception.InvalidEventException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class PatientCreatedEventReaderTest {

  /** The golden fixtures shared with the producer, relative to this module's directory. */
  private static final Path CONTRACTS = Path.of("..", "contracts", "events");

  private static final String EVENT_ID = "6f1c2a9e-3b7d-4e58-9a10-2c4d5e6f7a81";
  private static final String PATIENT_ID = "0b8e3f4a-7c21-4d9e-8f60-1a2b3c4d5e6f";

  // Strict on purpose: unknown-field tolerance must come from the reader itself, not from the
  // shared mapper happening to be lenient.
  private final PatientCreatedEventReader reader =
      new PatientCreatedEventReader(
          JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());

  @Test
  @DisplayName("Reads the v1 golden fixture")
  void readsGoldenFixture() throws IOException {
    PatientCreatedEvent event = reader.read(fixture("patient.created.v1.json"));

    assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
    assertThat(event.patientId()).isEqualTo(UUID.fromString(PATIENT_ID));
    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-07T12:34:56.789Z"));
  }

  @Test
  @DisplayName("Ignores fields it does not know (v1 fixture with an added field)")
  void ignoresUnknownFields() throws IOException {
    PatientCreatedEvent event = reader.read(fixture("patient.created.v1-unknown-field.json"));

    assertThat(event.eventId()).isEqualTo(UUID.fromString("9d2b7e14-5a3c-4f81-b6d0-7e8f9a0b1c2d"));
    assertThat(event.patientId())
        .isEqualTo(UUID.fromString("3c7d9e1f-2a4b-4c6d-8e0f-1a2b3c4d5e70"));
  }

  @Test
  @DisplayName("Rejects a different eventType")
  void rejectsWrongType() {
    assertInvalid(event("\"patient.updated\"", "1", quoted(EVENT_ID), quoted(PATIENT_ID)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "0", "1.5", "1.0", "null", "\"1\"", "\"one\"", "true", "[1]"})
  @DisplayName("Rejects any eventVersion other than the JSON integer 1 (no coercion)")
  void rejectsUnsupportedVersion(String version) {
    assertInvalid(event("\"patient.created\"", version, quoted(EVENT_ID), quoted(PATIENT_ID)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1791377696", "1791377696.789", "\"yesterday\"", "\"2026-10-07\"", "{}"})
  @DisplayName("Rejects an occurredAt that is not an ISO-8601 instant string (no coercion)")
  void rejectsNonStringOrInvalidOccurredAt(String occurredAt) {
    assertInvalid(
        """
        {"eventId":"%s","eventType":"patient.created","eventVersion":1,
         "occurredAt":%s,"patientId":"%s"}"""
            .formatted(EVENT_ID, occurredAt, PATIENT_ID));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "7", "[\"patient.created\"]"})
  @DisplayName("Rejects an eventType that is not the JSON string patient.created")
  void rejectsNonStringType(String type) {
    assertInvalid(event(type, "1", quoted(EVENT_ID), quoted(PATIENT_ID)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"1-1-1-1-1\"", "7", "null", "\"\""})
  @DisplayName("Rejects a patientId that is not a canonical UUID string")
  void rejectsMalformedPatientId(String patientId) {
    assertInvalid(event("\"patient.created\"", "1", quoted(EVENT_ID), patientId));
  }

  @Test
  @DisplayName("Rejects a missing eventId")
  void rejectsMissingEventId() {
    assertInvalid(
        """
        {"eventType":"patient.created","eventVersion":1,
         "occurredAt":"2026-10-07T12:34:56.789Z","patientId":"%s"}"""
            .formatted(PATIENT_ID));
  }

  @Test
  @DisplayName("Rejects a missing patientId")
  void rejectsMissingPatientId() {
    assertInvalid(
        """
        {"eventId":"%s","eventType":"patient.created","eventVersion":1,
         "occurredAt":"2026-10-07T12:34:56.789Z"}"""
            .formatted(EVENT_ID));
  }

  @Test
  @DisplayName("Rejects a missing occurredAt")
  void rejectsMissingOccurredAt() {
    assertInvalid(
        """
        {"eventId":"%s","eventType":"patient.created","eventVersion":1,"patientId":"%s"}"""
            .formatted(EVENT_ID, PATIENT_ID));
  }

  @Test
  @DisplayName("Rejects an eventId that is not a UUID")
  void rejectsMalformedEventId() {
    assertInvalid(event("\"patient.created\"", "1", "\"not-a-uuid\"", quoted(PATIENT_ID)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{not json", "[]", "null", "\"text\"", "   "})
  @DisplayName("Rejects payloads that are not a JSON object")
  void rejectsMalformedJson(String payload) {
    assertInvalid(payload);
  }

  @Test
  @DisplayName("Rejects a null payload (tombstone)")
  void rejectsNullPayload() {
    assertInvalid(null);
  }

  @Test
  @DisplayName("Rejection messages never quote the payload")
  void rejectionMessageDoesNotEchoPayload() {
    String payload = "{\"eventId\": \"secret-looking-value\", broken";

    assertThatThrownBy(() -> reader.read(payload))
        .isInstanceOf(InvalidEventException.class)
        .hasNoCause()
        .message()
        .doesNotContain("secret-looking-value");
  }

  @Test
  @DisplayName("eventIdOf extracts a valid eventId even from an otherwise invalid event")
  void eventIdOfInvalidEvent() {
    String payload = event("\"patient.created\"", "2", quoted(EVENT_ID), quoted(PATIENT_ID));

    assertThat(reader.eventIdOf(payload)).contains(UUID.fromString(EVENT_ID));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{not json", "{\"eventId\":\"not-a-uuid\"}", "{\"eventId\":7}", "[]"})
  @DisplayName("eventIdOf is empty, never throwing, when no valid eventId is present")
  void eventIdOfWithoutValidId(String payload) {
    assertThat(reader.eventIdOf(payload)).isEmpty();
  }

  private void assertInvalid(String payload) {
    assertThatThrownBy(() -> reader.read(payload)).isInstanceOf(InvalidEventException.class);
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(CONTRACTS.resolve(name));
  }

  private static String quoted(String value) {
    return "\"" + value + "\"";
  }

  private static String event(String type, String version, String eventId, String patientId) {
    return """
        {"eventId":%s,"eventType":%s,"eventVersion":%s,
         "occurredAt":"2026-10-07T12:34:56.789Z","patientId":%s}"""
        .formatted(eventId, type, version, patientId);
  }
}
