package com.simplematch.riskservice.routing;

/** Declared orders-validated partition topology carried by one policy. */
public record RoutingPolicyPartitionTopology(int partitionCount) {
  /** Requires a positive partition count. */
  public RoutingPolicyPartitionTopology {
    if (partitionCount <= 0) {
      throw new RoutingPolicyProjectionValidationException("partition count must be positive");
    }
  }

  /** Returns whether a partition belongs to this topology. */
  public boolean contains(int partition) {
    return partition >= 0 && partition < partitionCount;
  }
}
