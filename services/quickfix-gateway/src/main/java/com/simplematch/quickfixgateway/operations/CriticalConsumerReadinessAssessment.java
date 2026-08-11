package com.simplematch.quickfixgateway.operations;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies critical-consumer presence, identity, state, and per-partition progress rules. */
final class CriticalConsumerReadinessAssessment {
  private final TradingSystemReadinessThresholds thresholds;
  private final TradingSystemComponentAssessment componentAssessment;

  CriticalConsumerReadinessAssessment(
      TradingSystemReadinessThresholds thresholds,
      TradingSystemComponentAssessment componentAssessment) {
    this.thresholds = thresholds;
    this.componentAssessment = componentAssessment;
  }

  void assess(List<CriticalConsumerStatus> consumers, TradingSystemAssessment assessment) {
    final EnumSet<CriticalConsumer> observedConsumers = EnumSet.noneOf(CriticalConsumer.class);
    for (CriticalConsumerStatus consumer : consumers) {
      final String prefix = "CRITICAL_CONSUMER_" + consumer.component();
      if (!observedConsumers.add(consumer.component())) {
        assessment.pause(prefix + "_DUPLICATE_STATUS");
        continue;
      }
      componentAssessment.assessIdentity(prefix, consumer.identity(), assessment);
      componentAssessment.assessComponentState(prefix, consumer.state(), assessment);
      componentAssessment.assessFreshness(prefix, consumer.observedAt(), assessment);
      assessProgress(prefix, consumer.partitionProgress(), assessment);
    }
    for (CriticalConsumer consumer : CriticalConsumer.values()) {
      if (!observedConsumers.contains(consumer)) {
        assessment.pause("CRITICAL_CONSUMER_" + consumer + "_MISSING");
      }
    }
  }

  private void assessProgress(
      String componentPrefix,
      List<ConsumerPartitionProgress> progress,
      TradingSystemAssessment assessment) {
    final Set<Integer> observedPartitions = new HashSet<>();
    for (ConsumerPartitionProgress partition : progress) {
      final String prefix = componentPrefix + "_PARTITION_" + partition.partitionId();
      if (partition.partitionId() >= thresholds.expectedPartitionCount()) {
        assessment.interrupt(prefix + "_OUT_OF_RANGE");
        continue;
      }
      if (!observedPartitions.add(partition.partitionId())) {
        assessment.pause(prefix + "_DUPLICATE_PROGRESS");
        continue;
      }
      componentAssessment.assessOffsets(
          prefix, partition.committedOffset(), partition.endOffset(), assessment);
      componentAssessment.assessOldestUnprocessedAge(
          prefix, partition.oldestUnprocessedAge(), assessment);
    }
    for (int partitionId = 0; partitionId < thresholds.expectedPartitionCount(); partitionId++) {
      if (!observedPartitions.contains(partitionId)) {
        assessment.pause(componentPrefix + "_PARTITION_" + partitionId + "_MISSING");
      }
    }
    if (progress.size() != thresholds.expectedPartitionCount()) {
      assessment.pause(componentPrefix + "_PROGRESS_COUNT_INVALID");
    }
  }
}
