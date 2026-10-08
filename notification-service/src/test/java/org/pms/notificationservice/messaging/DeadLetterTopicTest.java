package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
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
 * The dead-letter topic as it really exists on the broker. Dead-lettered records keep their source
 * partition number, which only works when the dead-letter topic has at least as many partitions as
 * the source.
 */
class DeadLetterTopicTest extends AbstractPatientCreatedIntegrationTest {

  private static final int LAST_SOURCE_PARTITION = 2;

  @Autowired private KafkaAdmin kafkaAdmin;

  @Test
  @DisplayName("The dead-letter topic has at least as many partitions as patient.created")
  void deadLetterTopicCoversEverySourcePartition() throws Exception {
    Map<String, TopicDescription> topics;
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      topics =
          admin
              .describeTopics(List.of(properties.topic(), properties.deadLetterTopic()))
              .allTopicNames()
              .get(10, TimeUnit.SECONDS);
    }

    int source = topics.get(properties.topic()).partitions().size();
    int deadLetter = topics.get(properties.deadLetterTopic()).partitions().size();
    assertThat(source).isEqualTo(3);
    assertThat(deadLetter).isGreaterThanOrEqualTo(source);
  }

  @Test
  @DisplayName("A poison record from source partition 2 lands on dead-letter partition 2")
  void deadLetterKeepsSourcePartition() {
    String payload = "{not json " + UUID.randomUUID() + " " + PAYLOAD_MARKER;

    send(LAST_SOURCE_PARTITION, "partition-2-key", payload, "notif-dlt-p2");

    ConsumerRecord<String, String> deadLetter = awaitDeadLetter(payload);
    assertThat(deadLetter.partition()).isEqualTo(LAST_SOURCE_PARTITION);
    byte[] originalPartition =
        deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value();
    assertThat(ByteBuffer.wrap(originalPartition).getInt()).isEqualTo(LAST_SOURCE_PARTITION);
    assertThat(deadLetter.key()).isEqualTo("partition-2-key");
    assertThat(header(deadLetter, "X-Correlation-Id")).isEqualTo("notif-dlt-p2");
  }
}
