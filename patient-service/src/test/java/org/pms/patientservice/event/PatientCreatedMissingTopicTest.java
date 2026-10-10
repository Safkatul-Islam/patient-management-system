package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * The broker is up but the topic does not exist (the service's startup topic creation is off here,
 * standing in for a failed one) and the broker refuses to auto-create it: creates still succeed
 * fast, the lost event is visible, and the topic is still absent afterwards.
 */
@Testcontainers
@Import(PostgresTestcontainerConfig.class)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=false")
class PatientCreatedMissingTopicTest extends AbstractLostEventTest {

  private static final String TOPIC = "patient.created";

  /** Started here, so auto-creation is off before the application context exists. */
  @Container @ServiceConnection
  static final RedpandaContainer REDPANDA = startWithoutTopicAutoCreation();

  @Test
  @DisplayName("create answers 201 fast when the topic is missing; the topic is not auto-created")
  void createSucceedsWhenTheTopicIsMissing(CapturedOutput output) throws Exception {
    assertThat(rpk("cluster", "config", "get", "auto_create_topics_enabled").getStdout().trim())
        .isEqualTo("false");

    createAndExpectOneLostEvent(output, "Topic missing, broker up");

    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
      assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain(TOPIC);
    }
  }

  private static RedpandaContainer startWithoutTopicAutoCreation() {
    RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v26.2.2");
    redpanda.start();
    try {
      ExecResult result =
          redpanda.execInContainer(
              "rpk", "cluster", "config", "set", "auto_create_topics_enabled", "false");
      if (result.getExitCode() != 0) {
        throw new IllegalStateException("rpk config set failed: " + result.getStderr());
      }
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
    return redpanda;
  }

  private static ExecResult rpk(String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "rpk";
    System.arraycopy(args, 0, command, 1, args.length);
    ExecResult result = REDPANDA.execInContainer(command);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    return result;
  }
}
