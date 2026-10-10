package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An unreachable database must not dead-letter valid events. Under the default back-off (3 attempts
 * 1 s apart) this event would be on the dead-letter topic within about 5 s of the outage; with the
 * connectivity back-off it waits, and is applied exactly once when the database returns.
 */
class DatabaseOutageTest extends AbstractPatientCreatedIntegrationTest {

  /** Well past the old 3-attempt window (2 pauses of 1 s plus 3 attempts of ~1 s each). */
  private static final Duration OUTAGE = Duration.ofSeconds(10);

  private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(60);

  @Autowired private PostgreSQLContainer postgres;

  @Test
  @DisplayName("A paused database is waited out: no dead letter, one effect after it returns")
  void pausedDatabaseIsWaitedOut(CapturedOutput output) {
    UUID eventId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    String payload = event(eventId, patientId);
    double deadLetteredBefore = deadLetteredCount();
    DockerClient docker = DockerClientFactory.instance().client();

    docker.pauseContainerCmd(postgres.getContainerId()).exec();
    String coordinates;
    try {
      RecordMetadata sent = send(patientId.toString(), payload, "notif-outage-1");
      coordinates = sent.topic() + "-" + sent.partition() + "@" + sent.offset();

      await().pollDelay(OUTAGE).atMost(OUTAGE.plusSeconds(1)).until(() -> true);

      // At least as many failed attempts as the default back-off allows, yet not dead-lettered.
      List<String> failedAttempts = linesContaining(output, "failed for record " + coordinates);
      assertThat(failedAttempts).hasSizeGreaterThanOrEqualTo(3);
      assertThat(failedAttempts)
          .allSatisfy(
              line ->
                  assertThat(line)
                      .containsAnyOf(
                          "CannotCreateTransactionException",
                          "DataAccessResourceFailureException",
                          "CannotGetJdbcConnectionException",
                          "TransientDataAccessException"));
      verify(recorder, never()).record(argThat(event -> event.eventId().equals(eventId)));
      assertThat(deadLettersWithValue(payload)).isEmpty();
      assertThat(deadLetteredCount()).isEqualTo(deadLetteredBefore);
    } finally {
      docker.unpauseContainerCmd(postgres.getContainerId()).exec();
    }

    await().atMost(RECOVERY_TIMEOUT).until(() -> notificationsFor(eventId) == 1);
    assertThat(processedEventsFor(eventId)).isEqualTo(1);
    verify(recorder, times(1)).record(argThat(event -> event.eventId().equals(eventId)));
    assertThat(deadLettersWithValue(payload)).isEmpty();
    assertThat(deadLetteredCount()).isEqualTo(deadLetteredBefore);
    assertThat(linesContaining(output, "Dead-lettered record " + coordinates)).isEmpty();
  }
}
