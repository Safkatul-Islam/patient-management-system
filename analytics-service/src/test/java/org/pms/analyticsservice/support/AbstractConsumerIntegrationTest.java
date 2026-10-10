package org.pms.analyticsservice.support;

import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.pms.analyticsservice.service.DailyRegistrations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Runs the real listener container against Redpanda and Postgres. Every subclass declares the same
 * configuration, so they share one cached context (and one pair of containers).
 *
 * <p>Tests share the topics and the consumer group, so each one uses its own event IDs and its own
 * registration day, and waits for its records to be fully handled before it ends.
 *
 * <p>The short Hikari and socket timeouts make an unreachable database fail an attempt within a few
 * seconds, so the paused-database test stays fast.
 */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-timeout=2000",
      "spring.datasource.hikari.validation-timeout=1000",
      "spring.datasource.hikari.data-source-properties[socketTimeout]=3"
    })
@Import(ContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractConsumerIntegrationTest {

  protected static final String DEAD_LETTER_TOPIC = "patient.created.analytics-service.dlt";
  protected static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final AtomicLong NEXT_DAY = new AtomicLong();

  @MockitoSpyBean protected PatientCreatedEventReader reader;
  @MockitoSpyBean protected DailyRegistrations dailyRegistrations;

  @Autowired protected MeterRegistry meterRegistry;
  @Autowired protected RedpandaContainer redpanda;
  @Autowired protected JdbcClient jdbc;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;

  private KafkaProducer<String, String> producer;

  @BeforeEach
  void waitForPartitionsAndOpenProducer() {
    // Produce only once the group owns every partition, so no record races the assignment.
    listenerRegistry
        .getListenerContainers()
        .forEach(
            container ->
                ContainerTestUtils.waitForAssignment(
                    container, ContainersConfig.SOURCE_PARTITIONS));
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                redpanda.getBootstrapServers(),
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                10_000),
            new StringSerializer(),
            new StringSerializer());
  }

  @AfterEach
  void closeProducer() {
    producer.close(Duration.ofSeconds(5));
  }

  /** A day no other test counts registrations for, so counts start at zero. */
  protected static LocalDate unusedDay() {
    return LocalDate.of(2100, 1, 1).plusDays(NEXT_DAY.getAndIncrement());
  }

  protected static Instant noonOf(LocalDate day) {
    return day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC);
  }

  protected static String payload(UUID eventId, Instant occurredAt, int eventVersion) {
    return """
        {"eventId":"%s","eventType":"patient.created","eventVersion":%d,"occurredAt":"%s",\
        "patientId":"%s"}"""
        .formatted(eventId, eventVersion, occurredAt, UUID.randomUUID());
  }

  protected static String payload(UUID eventId, Instant occurredAt) {
    return payload(eventId, occurredAt, 1);
  }

  /** Sends and waits for the broker's acknowledgement; {@code partition} null lets the key pick. */
  protected RecordMetadata send(Integer partition, String key, String value, String correlationId)
      throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(ContainersConfig.SOURCE_TOPIC, partition, key, value);
    if (correlationId != null) {
      record.headers().add("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    return producer.send(record).get(10, TimeUnit.SECONDS);
  }

  protected long registrationsOn(LocalDate day) {
    return jdbc.sql("SELECT registrations FROM daily_registrations WHERE day = ?")
        .param(day)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  protected boolean isProcessed(UUID eventId) {
    return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?")
            .param(eventId)
            .query(Long.class)
            .single()
        == 1;
  }

  protected void awaitProcessed(UUID eventId) {
    await().atMost(TIMEOUT).until(() -> isProcessed(eventId));
  }

  protected double counterValue(String name) {
    Counter counter = meterRegistry.find(name).tag("consumer", "analytics-service").counter();
    return counter == null ? 0 : counter.count();
  }

  protected double deadLetteredCount() {
    return counterValue("pms.events.dead_lettered");
  }

  /** Waits until the dead-letter topic holds a record with this value and returns it. */
  protected ConsumerRecord<String, String> awaitDeadLetter(String value) {
    return await()
        .atMost(TIMEOUT)
        .until(() -> deadLettersWithValue(value), records -> !records.isEmpty())
        .get(0);
  }

  /** Every record with this value on the dead-letter topic, read from the start up to its end. */
  protected List<ConsumerRecord<String, String>> deadLettersWithValue(String value) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            redpanda.getBootstrapServers(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false);
    List<ConsumerRecord<String, String>> matches = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(DEAD_LETTER_TOPIC, Duration.ofSeconds(10)).stream()
              .map(info -> new TopicPartition(info.topic(), info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofSeconds(10));
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Could not read the dead-letter topic to its end");
        }
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
          if (value.equals(record.value())) {
            matches.add(record);
          }
        }
      }
    }
    return matches;
  }

  protected static String header(ConsumerRecord<?, ?> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
