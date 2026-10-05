#pragma once

#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"

#include <cstdint>
#include <string>

namespace simplematch::matching {

/** Complete infrastructure-neutral runtime fact exported by one Matching owner. */
struct MatchingRuntimeObservation {
  std::int32_t partition_id;
  std::string owner_id;
  std::string artifact_id;
  PinnedMatchingIdentity identity;
  MatchingRuntimeMetrics metrics;
  PartitionReplayStatus replay;
  std::string runtime_state;
  std::int64_t updated_at_epoch_ms;
};

/** Encodes one Matching owner observation as the status-sidecar JSON contract. */
[[nodiscard]] std::string encode_matching_runtime_observation(
    const MatchingRuntimeObservation &observation);

} // namespace simplematch::matching
