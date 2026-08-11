#pragma once

#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/bounded_spsc_ring.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <cstddef>
#include <memory>
#include <optional>

namespace simplematch::matching {

enum class MatchingRuntimeStep {
  kNoInput,
  kOwnershipDenied,
  kOutputBackpressured,
  kProcessed,
  kCoreRejected
};

/**
 * Owns the preallocated command and event rings around one strictly single-writer matching core.
 *
 * <p>Kafka polling and publication remain adapters outside this type. The runtime preserves an
 * input command in its ring until the output ring has enough fixed capacity for its worst case.
 */
class MatchingRuntime {
public:
  MatchingRuntime(
      std::unique_ptr<DeterministicMatchingCore> core,
      std::size_t input_capacity,
      std::size_t output_capacity,
      std::shared_ptr<const PartitionOwnershipPermit> ownership_permit);

  [[nodiscard]] bool submit(CoreCommand command);
  [[nodiscard]] MatchingRuntimeStep process_one();
  [[nodiscard]] std::size_t input_size() const;
  [[nodiscard]] std::size_t output_size() const;
  [[nodiscard]] std::size_t maximum_output_events() const;
  [[nodiscard]] std::optional<CoreEvent> take_output();
  [[nodiscard]] BoundedSpscRing<CoreEvent> &output_ring();

private:
  std::shared_ptr<const PartitionOwnershipPermit> ownership_permit_;
  std::unique_ptr<DeterministicMatchingCore> core_;
  BoundedSpscRing<CoreCommand> input_ring_;
  BoundedSpscRing<CoreEvent> output_ring_;
};

} // namespace simplematch::matching
