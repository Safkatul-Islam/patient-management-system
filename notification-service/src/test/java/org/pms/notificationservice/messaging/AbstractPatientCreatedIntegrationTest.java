package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.common.web.correlation.CorrelationIds;
import org.pms.notificationservice.config.PatientCreatedConsumerProperties;
import org.pms.notificationservice.service.WelcomeNotificationRecorder;
import org.pms.notificationservice.support.ContainersTestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Shared setup for the consumer's integration tests against real Postgres and Redpanda. Every
 * subclass uses exactly this configuration (spies included), so they share one cached context and
 * one pair of containers. Tests use fresh event and patient IDs and never assume empty tables.
 *
 * <p>Short Hikari timeouts make an unreachable database fail fast (1 s) instead of after the 30 s
 * default, so the outage test sees several attempts within seconds. The short socket timeout covers
 * a pooled connection that skips validation (used within the last 500 ms): its statement then fails
 * after 3 s instead of hanging on the paused server.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-timeout=1000",
      "spring.datasource.hikari.validation-timeout=500",
      "spring.datasource.hikari.data-source-properties[socketTimeout]=3"
    })
@Import(ContainersTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
abstract class AbstractPatientCreatedIntegrationTest {

  protected static final Duration TIMEOUT = Duration.ofSeconds(30);
  protected static final String METRIC = "pms.events.dead_lettered";

  /** In every payload this suite sends; it must never show up in any log line. */
  protected static final String PAYLOAD_MARKER = "payload-marker-never-logged";

  @Autowired protected KafkaTemplate<String, String> kafkaTemplate;
  @Autowired protected ConsumerFactory<String, String> consumerFactory;
  @Autowired protected KafkaListenerEndpointRegistry listenerRegistry;
  @Autowired protected JdbcTemplate jdbcTemplate;
  @Autowired protected PatientCreatedConsumerProperties properties;
  @Autowired protected MeterRegistry meterRegistry;

  @MockitoSpyBean protected PatientCreatedEventReader reader;
  @MockitoSpyBean protected WelcomeNotificationRecorder recorder;

  /** Records produced before the consumer owns its partitions would make timing-dependent tests. */
  @BeforeEach
  void waitForPartitionAssignment() {
    ContainerTestUtils.waitForAssignment(
        listenerRegistry.getListenerContainer(PatientCreatedListener.ID),
        ContainersTestConfig.SOURCE_PARTITIONS);
  }

  protected static String event(UUID eventId, UUID patientId) {
    return eventJson(eventId, patientId, 1);
  }

  protected static String eventJson(UUID eventId, UUID patientId, int version) {
    return """
        {"eventId":"%s","eventType":"patient.created","eventVersion":%d,\
        "occurredAt":"2026-10-07T12:34:56.789Z","patientId":"%s","laterField":"%s"}"""
        .formatted(eventId, version, patientId, PAYLOAD_MARKER);
  }

  protected RecordMetadata send(String key, String payload, String correlationId) {
    return send(null, key, payload, correlationId);
  }

  /** Sends and waits for the broker's acknowledgement; returns where the record was written. */
  protected RecordMetadata send(
      Integer partition, String key, String payload, String correlationId) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(properties.topic(), partition, key, payload);
    if (correlationId != null) {
      record.headers().add(CorrelationIds.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      return kafkaTemplate.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    } catch (Exception ex) {
      throw new IllegalStateException("Test record was not acknowledged by the broker", ex);
    }
  }

  /** Waits until a record with exactly this value is on the dead-letter topic, and returns it. */
  protected ConsumerRecord<String, String> awaitDeadLetter(String payload) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    await()
        .atMost(TIMEOUT)
        .pollInterval(Duration.ofMillis(500))
        .until(
            () -> {
              found.clear();
              found.addAll(deadLettersWithValue(payload));
              return !found.isEmpty();
            });
    return found.getFirst();
  }

  /**
   * Every record with exactly this value currently on the dead-letter topic: reads all partitions
   * from the beginning up to their end offsets at the time of the call, so an empty result is a
   * definite "not there", not a timeout.
   */
  protected List<ConsumerRecord<String, String>> deadLettersWithValue(String payload) {
    String topic = properties.deadLetterTopic();
    List<ConsumerRecord<String, String>> matches = new ArrayList<>();
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null)) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
              .map(info -> new TopicPartition(topic, info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(10));
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
        if (System.nanoTime() > deadline) {
          return fail("Could not read " + topic + " up to its end offsets within " + TIMEOUT);
        }
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
          if (payload.equals(record.value())) {
            matches.add(record);
          }
        }
      }
    }
    return matches;
  }

  protected static String header(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  protected static int originalPartition(ConsumerRecord<?, ?> deadLetter) {
    return ByteBuffer.wrap(binaryHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION)).getInt();
  }

  /** The failed source record as {@code topic-partition@offset}, as the consumer logs it. */
  protected static String sourceCoordinates(ConsumerRecord<?, ?> deadLetter) {
    long offset =
        ByteBuffer.wrap(binaryHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET)).getLong();
    return header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)
        + "-"
        + originalPartition(deadLetter)
        + "@"
        + offset;
  }

  private static byte[] binaryHeader(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null) {
      return fail("Dead-letter record has no " + name + " header");
    }
    return header.value();
  }

  protected double deadLetterFailuresCount() {
    return meterRegistry
        .get("pms.events.dead_letter.failures")
        .tag("consumer", "notification-service")
        .counter()
        .count();
  }

  protected double deadLetteredCount() {
    return meterRegistry.get(METRIC).tag("consumer", "notification-service").counter().count();
  }

  protected int notificationsFor(UUID eventId) {
    return count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId);
  }

  protected int processedEventsFor(UUID eventId) {
    return count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId);
  }

  private int count(String sql, UUID eventId) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, eventId);
    return count == null ? 0 : count;
  }

  protected static List<String> linesContaining(CapturedOutput output, String... fragments) {
    return output
        .getAll()
        .lines()
        .filter(line -> List.of(fragments).stream().allMatch(line::contains))
        .toList();
  }
}
