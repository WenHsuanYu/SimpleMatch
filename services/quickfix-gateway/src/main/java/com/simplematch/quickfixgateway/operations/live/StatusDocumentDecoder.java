package com.simplematch.quickfixgateway.operations.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.quickfixgateway.operations.MatchingPartitionStatus;
import com.simplematch.quickfixgateway.operations.OperationalComponentState;
import com.simplematch.quickfixgateway.operations.RiskStatus;
import com.simplematch.quickfixgateway.operations.TradingIdentity;
import java.time.Instant;

/** Strictly decodes the internal status documents at the Gateway boundary. */
final class StatusDocumentDecoder {
  private StatusDocumentDecoder() {}

  static RiskStatus riskStatus(JsonNode root) {
    final TradingIdentity identity = camelCaseIdentity(
        StatusDocumentFields.requiredObject(root, "identity"));
    return new RiskStatus(
        OperationalComponentState.READY,
        identity,
        StatusDocumentFields.requiredInstant(root, "observedAt"),
        "READY");
  }

  static MatchingPartitionStatus matchingPartition(
      JsonNode root, int expectedPartition, long endOffset) {
    final JsonNode admission = StatusDocumentFields.requiredObject(root, "admission");
    final int partition = StatusDocumentFields.requiredInt(admission, "partition_id");
    if (partition != expectedPartition) {
      throw new IllegalStateException("Matching partition identity does not match endpoint");
    }
    final long nextCommitOffset = StatusDocumentFields.requiredLong(root, "next_commit_offset");
    final long updatedAt = StatusDocumentFields.requiredLong(root, "updated_at_epoch_ms");
    final boolean permitted =
        StatusDocumentFields.requiredBoolean(admission, "ownership_permitted");
    final boolean recovered = StatusDocumentFields.requiredBoolean(admission, "recovery_complete");
    final String runtimeState = StatusDocumentFields.requiredText(root, "runtime_state");
    final String partitionState = StatusDocumentFields.requiredText(root, "partition_state");
    final boolean ready = "READY".equals(runtimeState) && "OPEN".equals(partitionState);
    return new MatchingPartitionStatus(
        partition,
        StatusDocumentFields.requiredText(admission, "owner_id"),
        ready ? OperationalComponentState.READY : OperationalComponentState.DEGRADED,
        snakeCaseIdentity(StatusDocumentFields.requiredObject(admission, "identity")),
        permitted,
        recovered,
        nextCommitOffset,
        endOffset,
        Instant.ofEpochMilli(updatedAt),
        ready ? "READY" : runtimeState + "/" + partitionState);
  }

  static CriticalConsumerOperationalStatus consumerObservation(JsonNode root) {
    return new CriticalConsumerOperationalStatus(
        StatusDocumentFields.requiredBoolean(root, "quarantined"),
        StatusDocumentFields.longMap(root, "committedOffsets"),
        StatusDocumentFields.longMap(root, "oldestUnprocessedAgeMillis"),
        StatusDocumentFields.requiredInstant(root, "observedAt"));
  }

  private static TradingIdentity camelCaseIdentity(JsonNode identity) {
    return new TradingIdentity(
        StatusDocumentFields.requiredText(identity, "tradingSessionId"),
        StatusDocumentFields.requiredText(identity, "artifactId"),
        StatusDocumentFields.requiredText(identity, "artifactContentSha256"),
        StatusDocumentFields.requiredInt(identity, "commandSchemaVersion"),
        StatusDocumentFields.requiredInt(identity, "eventSchemaVersion"),
        StatusDocumentFields.requiredText(identity, "matchingAlgorithmVersion"),
        StatusDocumentFields.requiredText(identity, "matchingImageIdentity"));
  }

  private static TradingIdentity snakeCaseIdentity(JsonNode identity) {
    final JsonNode artifact = StatusDocumentFields.requiredObject(identity, "artifact");
    return new TradingIdentity(
        StatusDocumentFields.requiredText(identity, "trading_session_id"),
        StatusDocumentFields.requiredText(artifact, "id"),
        StatusDocumentFields.requiredText(artifact, "content_sha256"),
        StatusDocumentFields.requiredInt(identity, "command_schema_version"),
        StatusDocumentFields.requiredInt(identity, "event_schema_version"),
        StatusDocumentFields.requiredText(identity, "matching_algorithm_version"),
        StatusDocumentFields.requiredText(identity, "matching_image_identity"));
  }
}
