package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * The dead-letter topic as it really exists on the broker. Dead-lettered records are partitioned by
 * their original key, not pinned to their source partition number.
 */
class DeadLetterTopicTest extends AbstractPatientCreatedIntegrationTest {

  @Autowired private KafkaAdmin kafkaAdmin;

  @Test
  @DisplayName("This service declares its dead-letter topic with the configured partitions")
  void deadLetterTopicExistsAsDeclared() throws Exception {
    Map<String, TopicDescription> topics;
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      topics =
          admin
              .describeTopics(List.of(properties.deadLetterTopic()))
              .allTopicNames()
              .get(10, TimeUnit.SECONDS);
    }

    assertThat(topics.get(properties.deadLetterTopic()).partitions())
        .hasSize(properties.deadLetterPartitions());
  }

  @Test
  @DisplayName("Same-key poison records from different source partitions share one DLT partition")
  void deadLettersArePartitionedByKey() {
    String key = "dlt-key-" + UUID.randomUUID();
    String fromPartition0 = "{not json " + UUID.randomUUID() + " " + PAYLOAD_MARKER;
    String fromPartition2 = "{not json " + UUID.randomUUID() + " " + PAYLOAD_MARKER;
    double before = deadLetteredCount();

    send(0, key, fromPartition0, "notif-dlt-p0");
    send(2, key, fromPartition2, "notif-dlt-p2");

    ConsumerRecord<String, String> first = awaitDeadLetter(fromPartition0);
    ConsumerRecord<String, String> second = awaitDeadLetter(fromPartition2);
    assertThat(second.partition()).isEqualTo(first.partition());
    assertThat(originalPartition(first)).isZero();
    assertThat(originalPartition(second)).isEqualTo(2);
    assertThat(first.key()).isEqualTo(key);
    assertThat(second.key()).isEqualTo(key);
    assertThat(header(first, "X-Correlation-Id")).isEqualTo("notif-dlt-p0");
    assertThat(header(second, "X-Correlation-Id")).isEqualTo("notif-dlt-p2");
    assertThat(header(second, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(properties.topic());
    assertThat(second.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
    assertThat(second.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
    await().atMost(TIMEOUT).until(() -> deadLetteredCount() == before + 2);
  }
}
