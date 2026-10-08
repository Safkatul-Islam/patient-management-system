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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.analyticsservice.event.PatientCreatedEventReader;
import org.pms.analyticsservice.model.DailyRegistration;
import org.pms.analyticsservice.repository.DailyRegistrationRepository;
import org.pms.analyticsservice.repository.ProcessedEventRepository;
import org.pms.analyticsservice.service.DailyRegistrations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
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
 */
@SpringBootTest
@Import(ContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractConsumerIntegrationTest {

  protected static final String DEAD_LETTER_TOPIC = "patient.created.analytics-service.dlt";
  protected static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final AtomicLong NEXT_DAY = new AtomicLong();

  @MockitoSpyBean protected PatientCreatedEventReader reader;
  @MockitoSpyBean protected DailyRegistrations dailyRegistrations;

  @Autowired protected ProcessedEventRepository processedEvents;
  @Autowired protected MeterRegistry meterRegistry;
  @Autowired protected RedpandaContainer redpanda;
  @Autowired private DailyRegistrationRepository dailyRegistrationRepository;
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
  protected void send(Integer partition, String key, String value, String correlationId)
      throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(ContainersConfig.SOURCE_TOPIC, partition, key, value);
    if (correlationId != null) {
      record.headers().add("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    producer.send(record).get(10, TimeUnit.SECONDS);
  }

  protected long registrationsOn(LocalDate day) {
    return dailyRegistrationRepository
        .findById(day)
        .map(DailyRegistration::getRegistrations)
        .orElse(0L);
  }

  protected void awaitProcessed(UUID eventId) {
    await().atMost(TIMEOUT).until(() -> processedEvents.existsById(eventId));
  }

  protected double deadLetteredCount() {
    Counter counter =
        meterRegistry
            .find("pms.events.dead_lettered")
            .tag("consumer", "analytics-service")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /** Reads the dead-letter topic from the beginning until the record with this value appears. */
  protected ConsumerRecord<String, String> awaitDeadLetter(String value) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            redpanda.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "dlt-reader-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (value.equals(record.value())) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("No dead letter with the expected value within " + TIMEOUT);
  }

  protected static String header(ConsumerRecord<?, ?> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
