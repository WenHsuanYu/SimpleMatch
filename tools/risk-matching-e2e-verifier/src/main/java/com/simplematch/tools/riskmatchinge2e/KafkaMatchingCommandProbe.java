package com.simplematch.tools.riskmatchinge2e;

import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies the physical Kafka records produced for one matching command.
 *
 * <p>The probe delegates Kafka observation mechanics to {@link KafkaRecordObserver}, then checks
 * command-specific delivery invariants and decodes the matching command payload.
 */
public final class KafkaMatchingCommandProbe implements AutoCloseable {
  private final KafkaRecordObserver observer;

  /** Creates a plaintext local-lab probe over the production-shaped topic contract. */
  public KafkaMatchingCommandProbe(String bootstrapServers, String topic, String runId) {
    observer = new KafkaRecordObserver(bootstrapServers, topic, runId);
  }

  /** Captures the end offset of every partition immediately before Risk submission. */
  public Map<Integer, Long> snapshotEndOffsets() {
    return observer.snapshotEndOffsets();
  }

  /**
   * Finds and verifies every copy of {@code commandId} after the observation boundary.
   *
   * <p>At-least-once delivery permits byte-identical duplicates. The probe therefore rejects a
   * record on the wrong partition or a same-key record with different bytes, but records the count
   * of identical physical deliveries instead of incorrectly requiring exactly-once Kafka delivery.
   *
   * @param commandId the matching command identifier
   * @param expectedPartition the partition recorded by the persisted artifact
   * @param offsetsBefore the partition offsets captured before Risk submission
   * @param timeout the maximum observation duration
   * @return verified evidence for the observed matching command
   */
  public ProbeResult awaitCommand(
      String commandId, int expectedPartition, Map<Integer, Long> offsetsBefore, Duration timeout) {

    final List<ObservedRecord> matches = observer.collectMatches(commandId, offsetsBefore, timeout);

    validateMatches(matches, commandId, expectedPartition);

    final ObservedRecord first = matches.getFirst();

    // Validate eagerly so a successful await always contains a valid MatchingCommand payload.
    decodeCommand(first.value());

    final RecordMetadata metadata =
        new RecordMetadata(first.partition(), first.offset(), first.timestamp(), first.key());

    return new ProbeResult(metadata, matches.size(), first.value());
  }

  /** Verifies partition placement and byte identity for all physical deliveries. */
  private static void validateMatches(List<ObservedRecord> matches, String commandId,
      int expectedPartition) {
    final ObservedRecord first = matches.getFirst();

    for (ObservedRecord match : matches) {
      if (match.partition() != expectedPartition) {
        throw new IllegalStateException("command_id=" + commandId + " appeared on partition "
                + match.partition() + " instead of persisted artifact partition "
                + expectedPartition);
      }

      if (!first.value().equals(match.value())) {
        throw new IllegalStateException("same command_id produced conflicting Kafka payload bytes "
                + "under at-least-once delivery");
      }
    }
  }

  /** Decodes the raw Kafka payload as a {@link MatchingCommand}. */
  private static MatchingCommand decodeCommand(Bytes payload) {
    try {
      return MatchingCommand.parseFrom(payload.toByteArray());
    } catch (IOException invalid) {
      throw new IllegalStateException("matching.commands value is not a MatchingCommand protobuf",
          invalid);
    }
  }

  /** Returns the SHA-256 digest of the payload as a lowercase hexadecimal string. */
  private static String sha256(Bytes payload) {
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray());
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK does not provide SHA-256", impossible);
    }
  }

  /** Closes the underlying Kafka observer. */
  @Override
  public void close() {
    observer.close();
  }

  /** Identifies the first physical Kafka record used as evidence. */
  public record RecordMetadata(int partition, long offset, long timestamp, String key) {

    /** Requires a non-null Kafka record key. */
    public RecordMetadata {
      Objects.requireNonNull(key, "record key is required");
    }
  }

  /** Evidence returned after all initial-delivery invariants have been checked. */
  public record ProbeResult(RecordMetadata metadata, int physicalDeliveryCount, Bytes payload) {

    /** Requires complete evidence and a positive physical delivery count. */
    public ProbeResult {
      Objects.requireNonNull(metadata, "record metadata is required");
      Objects.requireNonNull(payload, "payload is required");

      if (physicalDeliveryCount <= 0) {
        throw new IllegalArgumentException(
            "physical delivery count must be positive");
      }
    }

    /** Returns the partition of the first observed physical record. */
    public int partition() {
      return metadata.partition();
    }

    /** Returns the offset of the first observed physical record. */
    public long offset() {
      return metadata.offset();
    }

    /** Returns the timestamp of the first observed physical record. */
    public long timestamp() {
      return metadata.timestamp();
    }

    /** Returns the key of the first observed physical record. */
    public String key() {
      return metadata.key();
    }

    /** Decodes and returns the observed payload as a MatchingCommand. */
    public MatchingCommand command() {
      return decodeCommand(payload);
    }

    /** Returns the SHA-256 digest of the payload as lowercase hexadecimal text. */
    public String payloadSha256() {
      return sha256(payload);
    }

    /** Returns the payload encoded as Base64 text. */
    public String payloadBase64() {
      return payload.toBase64();
    }
  }
}