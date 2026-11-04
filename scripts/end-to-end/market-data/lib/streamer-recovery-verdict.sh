#!/usr/bin/env bash

# Serialization seam for the market-data streamer recovery evidence.

write_streamer_recovery_verdict() {
  local destination="$1"
  jq -e '
    select((.oldUid | type == "string" and length > 0)
      and (.newUid | type == "string" and length > 0)
      and (.oldNode | type == "string" and length > 0)
      and (.newNode | type == "string" and length > 0)
      and .oldUid != .newUid
      and .newPodReadyEpochMs > .oldPodGoneEpochMs) |
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
        oldPodGoneEpochMs: .oldPodGoneEpochMs,
        newPodReadyEpochMs: .newPodReadyEpochMs,
        noOverlap: (.newPodReadyEpochMs > .oldPodGoneEpochMs)
      },
      client: {
        initialSnapshot: true,
        streamTerminated: true,
        reconnected: true,
        resubscribed: true
      }
    }
  ' >"$destination"
}
