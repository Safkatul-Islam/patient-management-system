package org.pms.patientservice.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Reads every partition of one topic in tests. Partitions are assigned directly (no consumer
 * group), so there is no rebalance to wait for, and the start position is fixed when the reader is
 * created: a record produced afterwards cannot be missed.
 */
public final class TopicReader implements AutoCloseable {

  private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

  private final Consumer<String, String> consumer;
  private final List<TopicPartition> partitions;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  private TopicReader(List<String> bootstrapServers, String topic) {
    Map<String, Object> props =
        KafkaTestUtils.consumerProps(
            String.join(",", bootstrapServers), "topic-reader-" + UUID.randomUUID(), false);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = new KafkaConsumer<>(props);
    partitions =
        consumer.partitionsFor(topic, METADATA_TIMEOUT).stream()
            .map(info -> new TopicPartition(topic, info.partition()))
            .toList();
    consumer.assign(partitions);
  }

  /** Reads only records produced after this call. */
  public static TopicReader fromNow(List<String> bootstrapServers, String topic) {
    TopicReader reader = new TopicReader(bootstrapServers, topic);
    reader.consumer.seekToEnd(reader.partitions);
    // seekToEnd is lazy; resolving the positions now pins the start to "now".
    reader.partitions.forEach(partition -> reader.consumer.position(partition, METADATA_TIMEOUT));
    return reader;
  }

  public int partitionCount() {
    return partitions.size();
  }

  /**
   * Polls until a record keyed {@code key} has arrived or {@code timeout} has passed, then returns
   * every record received so far with that key.
   */
  public List<ConsumerRecord<String, String>> awaitKey(String key, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (withKey(key).isEmpty() && System.nanoTime() < deadline) {
      poll();
    }
    return withKey(key);
  }

  /** Keeps polling for {@code duration}, e.g. to give a late or duplicate record time to arrive. */
  public List<ConsumerRecord<String, String>> pollFor(Duration duration) {
    long deadline = System.nanoTime() + duration.toNanos();
    while (System.nanoTime() < deadline) {
      poll();
    }
    return List.copyOf(received);
  }

  public List<ConsumerRecord<String, String>> withKey(String key) {
    return received.stream().filter(record -> key.equals(record.key())).toList();
  }

  private void poll() {
    consumer.poll(POLL_INTERVAL).forEach(received::add);
  }

  @Override
  public void close() {
    consumer.close();
  }
}
