package com.simplematch.tools.riskmatchinge2e;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Owns Kafka consumer state and observes physical records within a bounded delivery window.
 *
 * <p>The observer uses a unique ephemeral consumer group, explicitly assigns every expected
 * partition, and seeks to offsets captured before Risk admission.
 */
final class KafkaRecordObserver implements AutoCloseable {
  private static final int EXPECTED_PARTITION_COUNT = 15;
  private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final KafkaConsumer<String, byte[]> consumer;
  private final String topic;
  private final List<TopicPartition> partitions;

  /** Creates a plaintext local-lab consumer over the production-shaped topic contract. */
  KafkaRecordObserver(String bootstrapServers, String topic, String runId) {
    this.topic = requireText(topic, "topic");

    final Properties properties = new Properties();
    properties.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        requireText(bootstrapServers, "bootstrap servers"));
    properties.put(
        ConsumerConfig.GROUP_ID_CONFIG,
        "simplematch-rm1-e2e-" + requireText(runId, "run id"));
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
        StringDeserializer.class.getName());
    properties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
        ByteArrayDeserializer.class.getName());
    properties.put(
        ConsumerConfig.CLIENT_ID_CONFIG,
        "simplematch-rm1-e2e-" + UUID.randomUUID());
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

    consumer = new KafkaConsumer<>(properties);

    final List<PartitionInfo> metadata =
        consumer.partitionsFor(this.topic, METADATA_TIMEOUT);

    if (metadata.size() != EXPECTED_PARTITION_COUNT) {
      consumer.close();

      throw new IllegalStateException(
          this.topic
              + " exposes "
              + metadata.size()
              + " partitions; expected "
              + EXPECTED_PARTITION_COUNT);
    }

    partitions =
        metadata.stream()
            .map(info -> new TopicPartition(this.topic, info.partition()))
            .sorted(Comparator.comparingInt(TopicPartition::partition))
            .toList();
  }

  /** Captures the current end offset of every expected partition. */
  Map<Integer, Long> snapshotEndOffsets() {
    final Map<TopicPartition, Long> endOffsets =
        consumer.endOffsets(partitions, METADATA_TIMEOUT);

    final Map<Integer, Long> result = new LinkedHashMap<>();

    for (TopicPartition partition : partitions) {
      result.put(partition.partition(), endOffsets.get(partition));
    }

    return Map.copyOf(result);
  }

  /**
   * Collects matching physical deliveries within a bounded observation window.
   *
   * <p>The observation starts from the supplied partition offsets. After the first matching
   * record appears, the observer freezes the then-current end offsets and drains every partition
   * to that boundary. This captures duplicates already published in the same delivery window
   * without turning the probe into an unbounded Kafka consumer.
   *
   * @param commandId the Kafka record key to match
   * @param offsetsBefore the partition offsets captured before Risk submission
   * @param timeout the maximum observation duration
   * @return immutable matching records in poll order
   */
  List<ObservedRecord> collectMatches(
      String commandId,
      Map<Integer, Long> offsetsBefore,
      Duration timeout) {
    final String requiredCommandId = requireText(commandId, "command id");

    Objects.requireNonNull(offsetsBefore, "offset boundary is required");
    Objects.requireNonNull(timeout, "timeout is required");

    seekToBoundary(offsetsBefore);

    final Instant deadline = Instant.now().plus(timeout);
    final List<ObservedRecord> matches = new ArrayList<>();
    Map<TopicPartition, Long> terminalEndOffsets = null;

    while (Instant.now().isBefore(deadline)) {
      pollMatches(requiredCommandId, matches);

      if (terminalEndOffsets == null && !matches.isEmpty()) {
        terminalEndOffsets =
            consumer.endOffsets(partitions, METADATA_TIMEOUT);
      }

      if (terminalEndOffsets != null && reachedAll(terminalEndOffsets)) {
        break;
      }
    }

    if (matches.isEmpty()) {
      throw new IllegalStateException(
          "timed out waiting for command_id="
              + requiredCommandId
              + " on "
              + topic);
    }

    return List.copyOf(matches);
  }

  /** Polls once and appends records whose key matches the expected command identity. */
  private void pollMatches(String commandId, List<ObservedRecord> matches) {
    for (ConsumerRecord<String, byte[]> record : consumer.poll(POLL_INTERVAL)) {
      if (commandId.equals(record.key())) {
        matches.add(toObservedRecord(record));
      }
    }
  }

  /** Converts a consumed Kafka record to its immutable observed representation. */
  private static ObservedRecord toObservedRecord(
      ConsumerRecord<String, byte[]> record) {
    final byte[] value =
        Objects.requireNonNull(
            record.value(),
            "matching record value is required");

    return new ObservedRecord(
        record.partition(),
        record.offset(),
        record.timestamp(),
        record.key(),
        new Bytes(value));
  }

  /** Assigns all expected partitions and seeks to the supplied observation boundary. */
  private void seekToBoundary(Map<Integer, Long> offsetsBefore) {
    if (offsetsBefore.size() != EXPECTED_PARTITION_COUNT) {
      throw new IllegalArgumentException(
          "offset boundary must cover all 15 partitions");
    }

    consumer.assign(partitions);

    for (TopicPartition partition : partitions) {
      final Long offset = offsetsBefore.get(partition.partition());

      if (offset == null || offset < 0) {
        throw new IllegalArgumentException(
            "missing or invalid baseline offset for partition "
                + partition.partition());
      }

      consumer.seek(partition, offset);
    }
  }

  /** Returns whether every assigned partition reached its frozen terminal end offset. */
  private boolean reachedAll(Map<TopicPartition, Long> terminalEndOffsets) {
    for (TopicPartition partition : partitions) {
      if (consumer.position(partition) < terminalEndOffsets.get(partition)) {
        return false;
      }
    }

    return true;
  }

  /** Requires a non-blank text value. */
  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }

    return value;
  }

  /** Closes the Kafka consumer within the configured shutdown timeout. */
  @Override
  public void close() {
    consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
  }
}