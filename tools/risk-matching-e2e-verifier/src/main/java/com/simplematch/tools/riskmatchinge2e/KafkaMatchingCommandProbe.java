package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads the deployed {@code matching.commands} topic without committing offsets.
 *
 * <p>The probe owns a unique ephemeral consumer group, explicitly assigns all 15 partitions, and
 * seeks to offsets captured before Risk admission. This avoids the unsafe assumption that a
 * certification topic starts empty and lets the verifier prove that the command did not appear on
 * a different partition after the observation boundary.
 */
public final class KafkaMatchingCommandProbe implements AutoCloseable {
  private static final int EXPECTED_PARTITION_COUNT = 15;
  private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  private final KafkaConsumer<String, byte[]> consumer;
  private final String topic;
  private final List<TopicPartition> partitions;

  /** Creates a plaintext local-lab consumer over the production-shaped topic contract. */
  public KafkaMatchingCommandProbe(String bootstrapServers, String topic, String runId) {
    this.topic = requireText(topic, "topic");
    final Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, requireText(bootstrapServers, "bootstrap servers"));
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "simplematch-rm1-e2e-" + requireText(runId, "run id"));
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "simplematch-rm1-e2e-" + UUID.randomUUID());
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    consumer = new KafkaConsumer<>(properties);

    final List<PartitionInfo> metadata = consumer.partitionsFor(this.topic, METADATA_TIMEOUT);
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

  /** Captures the end offset of every partition immediately before Risk submission. */
  public Map<Integer, Long> snapshotEndOffsets() {
    final Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, METADATA_TIMEOUT);
    final Map<Integer, Long> result = new LinkedHashMap<>();
    for (TopicPartition partition : partitions) {
      result.put(partition.partition(), endOffsets.get(partition));
    }
    return Map.copyOf(result);
  }

  /**
   * Finds every copy of {@code commandId} published after the supplied observation boundary.
   *
   * <p>At-least-once delivery permits byte-identical duplicates. The probe therefore rejects a
   * record on the wrong partition or a same-key record with different bytes, but records the count
   * of identical physical deliveries instead of incorrectly requiring exactly-once Kafka delivery.
   */
  public ProbeResult awaitCommand(
      String commandId,
      int expectedPartition,
      Map<Integer, Long> offsetsBefore,
      Duration timeout) {
    Objects.requireNonNull(offsetsBefore, "offset boundary is required");
    Objects.requireNonNull(timeout, "timeout is required");
    if (offsetsBefore.size() != EXPECTED_PARTITION_COUNT) {
      throw new IllegalArgumentException("offset boundary must cover all 15 partitions");
    }

    consumer.assign(partitions);
    for (TopicPartition partition : partitions) {
      final Long offset = offsetsBefore.get(partition.partition());
      if (offset == null || offset < 0) {
        throw new IllegalArgumentException(
            "missing or invalid baseline offset for partition " + partition.partition());
      }
      consumer.seek(partition, offset);
    }

    final Instant deadline = Instant.now().plus(timeout);
    final List<ObservedRecord> matches = new ArrayList<>();
    Map<TopicPartition, Long> terminalEndOffsets = null;

    while (Instant.now().isBefore(deadline)) {
      for (ConsumerRecord<String, byte[]> record : consumer.poll(POLL_INTERVAL)) {
        if (commandId.equals(record.key())) {
          matches.add(
              new ObservedRecord(
                  record.partition(), record.offset(), record.timestamp(), record.key(), record.value()));
        }
      }

      // Once the first matching record is visible, freeze the then-current end offsets and drain to
      // those offsets. This captures physical duplicates already published by the same delivery
      // window without turning the verifier into an unbounded tailing consumer.
      if (!matches.isEmpty() && terminalEndOffsets == null) {
        terminalEndOffsets = consumer.endOffsets(partitions, METADATA_TIMEOUT);
      }
      if (terminalEndOffsets != null && reachedAll(terminalEndOffsets)) {
        break;
      }
    }

    if (matches.isEmpty()) {
      throw new IllegalStateException(
          "timed out waiting for command_id=" + commandId + " on " + topic);
    }

    final ObservedRecord first = matches.getFirst();
    if (first.partition() != expectedPartition) {
      throw new IllegalStateException(
          "command_id="
              + commandId
              + " appeared on partition "
              + first.partition()
              + " instead of persisted artifact partition "
              + expectedPartition);
    }
    for (ObservedRecord match : matches) {
      if (match.partition() != expectedPartition) {
        throw new IllegalStateException(
            "same command_id appeared on conflicting partition " + match.partition());
      }
      if (!Arrays.equals(first.value(), match.value())) {
        throw new IllegalStateException(
            "same command_id produced conflicting Kafka payload bytes under at-least-once delivery");
      }
    }

    final MatchingCommand decoded;
    try {
      decoded = MatchingCommand.parseFrom(first.value());
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("matching.commands value is not a MatchingCommand protobuf", invalid);
    }

    return new ProbeResult(
        first.partition(),
        first.offset(),
        first.timestamp(),
        matches.size(),
        first.key(),
        first.value(),
        sha256(first.value()),
        Base64.getEncoder().encodeToString(first.value()),
        decoded);
  }

  private boolean reachedAll(Map<TopicPartition, Long> terminalEndOffsets) {
    for (TopicPartition partition : partitions) {
      if (consumer.position(partition) < terminalEndOffsets.get(partition)) {
        return false;
      }
    }
    return true;
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK does not provide SHA-256", impossible);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  @Override
  public void close() {
    consumer.close(Duration.ofSeconds(5));
  }

  /** Immutable details for one physical Kafka record matching the run command identity. */
  private record ObservedRecord(
      int partition, long offset, long timestamp, String key, byte[] value) {}

  /**
   * Evidence returned to the orchestrator after all initial-delivery invariants have been checked.
   */
  public record ProbeResult(
      int partition,
      long offset,
      long timestamp,
      int physicalDeliveryCount,
      String key,
      byte[] payload,
      String payloadSha256,
      String payloadBase64,
      MatchingCommand command) {
    /** Requires the raw and decoded forms used by later evidence comparison. */
    public ProbeResult {
      payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload is required"), payload.length);
      Objects.requireNonNull(key, "record key is required");
      Objects.requireNonNull(payloadSha256, "payload hash is required");
      Objects.requireNonNull(payloadBase64, "payload base64 is required");
      Objects.requireNonNull(command, "decoded command is required");
      if (physicalDeliveryCount <= 0) {
        throw new IllegalArgumentException("physical delivery count must be positive");
      }
    }

    /** Returns a defensive copy because Kafka record bytes are mutable arrays. */
    @Override
    public byte[] payload() {
      return Arrays.copyOf(payload, payload.length);
    }
  }
}
