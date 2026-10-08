package org.pms.analyticsservice.event;

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
import tools.jackson.databind.json.JsonMapper;

class PatientCreatedEventReaderTest {

  private static final Path FIXTURES = Path.of("..", "contracts", "events");

  private static final String VALID =
      """
      {"eventId":"6f1c2a9e-3b7d-4e58-9a10-2c4d5e6f7a81","eventType":"patient.created",
       "eventVersion":1,"occurredAt":"2026-10-07T12:34:56.789Z",
       "patientId":"0b8e3f4a-7c21-4d9e-8f60-1a2b3c4d5e6f"}
      """;

  // A plain mapper, not Boot's: the reader must be tolerant whatever the mapper's defaults are.
  private final PatientCreatedEventReader reader =
      new PatientCreatedEventReader(JsonMapper.builder().build());

  @Test
  @DisplayName("Reads the v1 golden fixture")
  void readsGoldenFixture() throws IOException {
    PatientCreatedEvent event = reader.read(fixture("patient.created.v1.json"));

    assertThat(event.eventId()).isEqualTo(UUID.fromString("6f1c2a9e-3b7d-4e58-9a10-2c4d5e6f7a81"));
    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-07T12:34:56.789Z"));
    assertThat(event.patientId())
        .isEqualTo(UUID.fromString("0b8e3f4a-7c21-4d9e-8f60-1a2b3c4d5e6f"));
  }

  @Test
  @DisplayName("Ignores a field added later within version 1")
  void ignoresUnknownField() throws IOException {
    PatientCreatedEvent event = reader.read(fixture("patient.created.v1-unknown-field.json"));

    assertThat(event.eventId()).isEqualTo(UUID.fromString("9d2b7e14-5a3c-4f81-b6d0-7e8f9a0b1c2d"));
    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-07T12:35:10Z"));
  }

  @Test
  @DisplayName("Rejects another event type")
  void rejectsWrongEventType() {
    assertInvalid(VALID.replace("\"patient.created\"", "\"patient.updated\""));
  }

  @Test
  @DisplayName("Rejects an unsupported eventVersion")
  void rejectsVersion2() {
    assertInvalid(VALID.replace("\"eventVersion\":1", "\"eventVersion\":2"));
  }

  @ParameterizedTest(name = "missing {0}")
  @ValueSource(strings = {"eventId", "eventType", "eventVersion", "occurredAt", "patientId"})
  @DisplayName("Rejects a payload missing a required field")
  void rejectsMissingField(String field) {
    String payload = VALID.replace("\"" + field + "\"", "\"renamed_" + field + "\"");

    assertInvalid(payload);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"eventId\": \"6f1c2a9e",
        "not json at all",
        "",
        "null",
        "[1, 2]",
      })
  @DisplayName("Rejects a payload that is not a JSON event object")
  void rejectsMalformedJson(String payload) {
    assertInvalid(payload);
  }

  @Test
  @DisplayName("Rejects a record without a payload")
  void rejectsNullPayload() {
    assertInvalid(null);
  }

  @Test
  @DisplayName("Rejects an eventId that is not a UUID, without quoting it in the message")
  void rejectsNonUuidEventId() {
    String payload = VALID.replace("6f1c2a9e-3b7d-4e58-9a10-2c4d5e6f7a81", "secret-looking-value");

    assertThatThrownBy(() -> reader.read(payload))
        .isInstanceOf(InvalidEventException.class)
        .hasNoCause()
        .message()
        .doesNotContain("secret-looking-value");
  }

  @Test
  @DisplayName("eventIdOf returns a parsed UUID only")
  void eventIdOfIsBestEffort() {
    assertThat(reader.eventIdOf(VALID.replace("\"eventVersion\":1", "\"eventVersion\":2")))
        .contains(UUID.fromString("6f1c2a9e-3b7d-4e58-9a10-2c4d5e6f7a81"));
    assertThat(reader.eventIdOf("{\"eventId\":\"not-a-uuid\"}")).isEmpty();
    assertThat(reader.eventIdOf("{\"eventId\":")).isEmpty();
    assertThat(reader.eventIdOf(null)).isEmpty();
  }

  private void assertInvalid(String payload) {
    assertThatThrownBy(() -> reader.read(payload))
        .isInstanceOf(InvalidEventException.class)
        .hasNoCause();
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(FIXTURES.resolve(name));
  }
}
