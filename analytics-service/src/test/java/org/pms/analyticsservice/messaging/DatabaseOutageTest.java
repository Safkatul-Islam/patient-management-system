package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.analyticsservice.event.PatientCreatedEvent;
import org.pms.analyticsservice.support.AbstractConsumerIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database outage longer than the default 3-attempt window must not dead-letter a valid event:
 * connectivity failures back off exponentially, and the event is applied once the database is back.
 */
class DatabaseOutageTest extends AbstractConsumerIntegrationTest {

  // The default policy (3 attempts, 1 s apart, 2 s per failed connection attempt) would have
  // dead-lettered the event after about 8 s.
  private static final Duration OUTAGE = Duration.ofSeconds(11);

  @Autowired private PostgreSQLContainer postgres;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("A valid event survives a database outage and is applied once afterwards")
  void validEventSurvivesOutage(CapturedOutput output) throws Exception {
    UUID eventId = UUID.randomUUID();
    LocalDate day = unusedDay();
    String event = payload(eventId, noonOf(day));
    double deadLetteredBefore = deadLetteredCount();
    RecordMetadata sent;
    pause();
    try {
      // No pooled connection survives into the outage: every attempt has to connect.
      dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
      sent = send(null, UUID.randomUUID().toString(), event, "anlt-outage-1");
      Thread.sleep(OUTAGE.toMillis());

      assertThat(deadLettersWithValue(event)).isEmpty();
      assertThat(deadLetteredCount()).isEqualTo(deadLetteredBefore);
      verify(dailyRegistrations, never()).record(any(PatientCreatedEvent.class));
      assertThat(attemptLines(output, sent)).hasSizeGreaterThanOrEqualTo(3);
    } finally {
      unpause();
    }

    awaitProcessed(eventId);
    await().atMost(TIMEOUT).until(() -> registrationsOn(day) == 1);
    verify(dailyRegistrations, times(1)).record(any(PatientCreatedEvent.class));
    assertThat(deadLettersWithValue(event)).isEmpty();
    assertThat(deadLetteredCount()).isEqualTo(deadLetteredBefore);
  }

  private void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(postgres.getContainerId()).exec();
  }

  private void unpause() {
    DockerClientFactory.instance().client().unpauseContainerCmd(postgres.getContainerId()).exec();
  }

  private static List<String> attemptLines(CapturedOutput output, RecordMetadata sent) {
    String marker = "partition=" + sent.partition() + " offset=" + sent.offset() + " ";
    return output
        .getAll()
        .lines()
        .filter(line -> line.contains("Delivery attempt failed") && line.contains(marker))
        .toList();
  }
}
