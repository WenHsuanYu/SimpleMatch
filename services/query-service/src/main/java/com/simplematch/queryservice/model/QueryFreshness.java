package com.simplematch.queryservice.model;

import java.util.List;

/** Durable source-position and recovery metadata exposed with query reads. */
public record QueryFreshness(List<PartitionFreshness> partitions) {
  /** Preserves deterministic response ordering. */
  public QueryFreshness {
    partitions = List.copyOf(partitions);
  }

  /** Freshness state for one source topic partition. */
  public record PartitionFreshness(
      String sourceTopic,
      int partition,
      long lastProcessedOffset,
      String recoveryState,
      long updatedAtUnixMs) {}
}
