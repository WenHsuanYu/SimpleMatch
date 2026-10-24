package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.model.QueryFreshness;

/** Business v1 envelope that discloses only a conservative recorded projection-state summary. */
public record QueryReadResponse<T>(T data, Freshness freshness) {
  /**
   * Summarizes recorded diagnostics without exposing transport checkpoints or claiming catch-up.
   *
   * @param data business projection from the caller's read snapshot
   * @param checkpoints recorded diagnostics from the same snapshot, or absent diagnostics
   * @return the minimal business response envelope
   */
  public static <T> QueryReadResponse<T> fromProjection(T data, QueryFreshness checkpoints) {
    return new QueryReadResponse<>(data, new Freshness(projectionState(checkpoints)));
  }

  /** Minimal diagnostics; no timestamp, source coverage, source position or business revision. */
  public record Freshness(ProjectionState projectionState) {}

  /** Describes recorded checkpoints, never authoritative account capacity or current source lag. */
  public enum ProjectionState {
    /** At least one recorded source reports a known gap requiring recovery. */
    RECOVERY_REQUIRED,
    /** Diagnostics are absent, empty or contain an unsupported recovery state. */
    UNKNOWN,
    /** Every checkpoint in a nonempty recorded set is READY; coverage is not implied. */
    NO_GAP_RECORDED
  }

  private static ProjectionState projectionState(QueryFreshness checkpoints) {
    if (checkpoints == null || checkpoints.partitions().isEmpty()) {
      return ProjectionState.UNKNOWN;
    }
    boolean allReady = true;
    for (QueryFreshness.PartitionFreshness partition : checkpoints.partitions()) {
      if ("GAP_DETECTED".equals(partition.recoveryState())) {
        return ProjectionState.RECOVERY_REQUIRED;
      }
      allReady &= "READY".equals(partition.recoveryState());
    }
    return allReady ? ProjectionState.NO_GAP_RECORDED : ProjectionState.UNKNOWN;
  }
}
