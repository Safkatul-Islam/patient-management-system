package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.fail;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Shared setup for the consumer's integration tests against real Postgres and Redpanda. Every
 * subclass uses exactly this configuration (spies included), so they share one cached context and
 * one pair of containers. Tests use fresh event and patient IDs and never assume empty tables.
 */
@SpringBootTest
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

  protected void send(String key, String payload, String correlationId) {
    send(null, key, payload, correlationId);
  }

  protected void send(Integer partition, String key, String payload, String correlationId) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(properties.topic(), partition, key, payload);
    if (correlationId != null) {
      record.headers().add(CorrelationIds.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new IllegalStateException("Test record was not acknowledged by the broker", ex);
    }
  }

  /** Reads the dead-letter topic from the start until a record with this exact value appears. */
  protected ConsumerRecord<String, String> awaitDeadLetter(String payload) {
    String topic = properties.deadLetterTopic();
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null)) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(topic, info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (payload.equals(record.value())) {
            return record;
          }
        }
      }
    }
    return fail("Record did not reach " + topic + " within " + TIMEOUT);
  }

  protected static String header(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
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
