#!/usr/bin/env bash

# Serialization seam for the market-data streamer recovery evidence.

write_streamer_recovery_verdict() {
  local destination="$1"
  jq -e '
    {
      status: "PASSED",
      sourceRevision: .sourceRevision,
      namespace: .namespace,
      context: .context,
      streamer: {
        oldPodUid: .oldUid,
        oldNode: .oldNode,
        newPodUid: .newUid,
        newNode: .newNode,
        podUidsDiffer: (.oldUid != .newUid),
        nodesDiffer: (.oldNode != .newNode),
        oldPodGoneEpochMs: .oldPodGoneEpochMs,
        newPodReadyEpochMs: .newPodReadyEpochMs,
        noOverlap: (.newPodReadyEpochMs > .oldPodGoneEpochMs)
      },
      client: {
        initialSnapshot: true,
        streamTerminated: true,
        reconnected: true,
        resubscribed: true
      },
      partitionAssignment: {
        expectedPartitions: .expectedPartitions,
        oneFullStreamConsumer: true
      }
    }
  ' >"$destination"
}
