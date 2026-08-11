package com.simplematch.riskservice.admission;

import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.CloseBarrier;
import com.simplematch.contracts.matching.runtime.v1.CommandHeader;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.contracts.matching.runtime.v1.OpenBarrier;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates deterministic Open and Close Barrier commands for every partition of the fixed fleet. */
public final class MatchingBarrierOutboxFactory {
  private static final int PARTITION_COUNT = 15;
  private final String topic;
  private final VerifiedMarketReferenceArtifact artifact;
  private final String matchingImageDigest;
  private final Clock clock;

  /** Creates the daily barrier factory over one verified final artifact. */
  public MatchingBarrierOutboxFactory(
      String topic,
      VerifiedMarketReferenceArtifact artifact,
      String matchingImageDigest,
      Clock clock) {
    this.topic = requireNonBlank(topic, "topic");
    this.artifact = Objects.requireNonNull(artifact, "artifact");
    this.matchingImageDigest = requireImageDigest(matchingImageDigest);
    this.clock = Objects.requireNonNull(clock, "clock");
    if (artifact.artifact().routingPolicy().partitionCount() != PARTITION_COUNT) {
      throw new IllegalArgumentException("final artifact must declare exactly 15 partitions");
    }
  }

  /** Returns an Open Barrier record for partitions 0 through 14 in numeric order. */
  public List<OutboxRecord> open(String tradingSessionId) {
    return barriers(requireSession(tradingSessionId), BarrierKind.OPEN);
  }

  /** Returns a Close Barrier record for partitions 0 through 14 in numeric order. */
  public List<OutboxRecord> close(String tradingSessionId) {
    return barriers(requireSession(tradingSessionId), BarrierKind.CLOSE);
  }

  private List<OutboxRecord> barriers(String tradingSessionId, BarrierKind kind) {
    final List<OutboxRecord> records = new ArrayList<>(PARTITION_COUNT);
    final long createdAt = clock.millis();
    for (int partition = 0; partition < PARTITION_COUNT; partition++) {
      final String commandId = commandId(tradingSessionId, partition, kind);
      final MatchingCommand command = command(commandId, tradingSessionId, partition, kind);
      records.add(
          OutboxRecord.create(
              new OutboxRecord.EventInfo(commandId, createdAt),
              OutboxRecord.Routing.withPartition(topic, commandId, partition),
              new OutboxRecord.PayloadEnvelope(
                  command.toByteArray(),
                  MatchingCommand.getDescriptor().getFullName(),
                  "{\"schema_version\":\"matching-command-v1\"}"),
              new OutboxRecord.AggregateRef("trading_session", tradingSessionId)));
    }
    return List.copyOf(records);
  }

  private MatchingCommand command(
      String commandId, String tradingSessionId, int partition, BarrierKind kind) {
    final var identity = artifact.identity();
    final CommandHeader header =
        CommandHeader.newBuilder()
            .setSchemaVersion(1)
            .setCommandId(commandId)
            .setTradingSessionId(tradingSessionId)
            .setPartitionId(partition)
            .setArtifactIdentity(
                ArtifactIdentity.newBuilder()
                    .setTradingDay(identity.tradingDay().toString())
                    .setContentSha256(identity.contentSha256()))
            .setRoutingAlgorithmVersion(artifact.artifact().routingPolicy().algorithmVersion())
            .build();
    if (kind == BarrierKind.OPEN) {
      return MatchingCommand.newBuilder()
          .setHeader(header)
          .setOpenBarrier(
              OpenBarrier.newBuilder()
                  .setExpectedPartitionCount(PARTITION_COUNT)
                  .setEventSchemaVersion(1)
                  .setEventIdentityVersion(1)
                  .setMatchingImageDigest(matchingImageDigest))
          .build();
    }
    return MatchingCommand.newBuilder()
        .setHeader(header)
        .setCloseBarrier(CloseBarrier.getDefaultInstance())
        .build();
  }

  private String requireSession(String tradingSessionId) {
    final String expected = artifact.identity().tradingDay() + "-regular";
    if (!expected.equals(tradingSessionId)) {
      throw new IllegalArgumentException("trading session must match the final artifact day");
    }
    return tradingSessionId;
  }

  private String commandId(String tradingSessionId, int partition, BarrierKind kind) {
    final String input =
        "matching-barrier-command-v1\u001f"
            + kind
            + '\u001f'
            + artifact.identity().value()
            + '\u001f'
            + tradingSessionId
            + '\u001f'
            + partition;
    return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String requireImageDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("matching image digest must be a canonical sha256 digest");
    }
    return value;
  }

  private enum BarrierKind {
    OPEN,
    CLOSE
  }
}
