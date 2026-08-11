package com.simplematch.quickfixgateway.operations;

import java.util.HashSet;
import java.util.Set;

/** Applies the fixed-fleet owner, identity, recovery, and offset readiness rules. */
final class MatchingFleetReadinessAssessment {
  private final TradingSystemReadinessThresholds thresholds;
  private final TradingSystemComponentAssessment componentAssessment;

  MatchingFleetReadinessAssessment(
      TradingSystemReadinessThresholds thresholds,
      TradingSystemComponentAssessment componentAssessment) {
    this.thresholds = thresholds;
    this.componentAssessment = componentAssessment;
  }

  void assess(MatchingFleetStatus fleet, TradingSystemAssessment assessment) {
    componentAssessment.assessFreshness("MATCHING_FLEET", fleet.observedAt(), assessment);
    final Set<Integer> observedPartitions = new HashSet<>();
    for (MatchingPartitionStatus partition : fleet.partitions()) {
      assessPartition(partition, observedPartitions, assessment);
    }
    for (int partitionId = 0; partitionId < thresholds.expectedPartitionCount(); partitionId++) {
      if (!observedPartitions.contains(partitionId)) {
        assessment.pause("MATCHING_PARTITION_" + partitionId + "_MISSING");
      }
    }
    if (fleet.partitions().size() != thresholds.expectedPartitionCount()) {
      assessment.pause("MATCHING_OWNER_COUNT_INVALID");
    }
  }

  private void assessPartition(
      MatchingPartitionStatus partition,
      Set<Integer> observedPartitions,
      TradingSystemAssessment assessment) {
    final String prefix = "MATCHING_PARTITION_" + partition.partitionId();
    if (partition.partitionId() >= thresholds.expectedPartitionCount()) {
      assessment.interrupt(prefix + "_OUT_OF_RANGE");
      return;
    }
    if (!observedPartitions.add(partition.partitionId())) {
      assessment.interrupt(prefix + "_DUPLICATE_OWNER");
      return;
    }
    componentAssessment.assessIdentity(prefix, partition.identity(), assessment);
    componentAssessment.assessComponentState(prefix, partition.state(), assessment);
    componentAssessment.assessFreshness(prefix, partition.observedAt(), assessment);
    if (!partition.ownershipPermit()) {
      assessment.pause(prefix + "_OWNERSHIP_PERMIT_MISSING");
    }
    if (!partition.recoveryComplete()) {
      assessment.pause(prefix + "_RECOVERY_INCOMPLETE");
    }
    componentAssessment.assessOffsets(
        prefix, partition.committedOffset(), partition.endOffset(), assessment);
  }
}
