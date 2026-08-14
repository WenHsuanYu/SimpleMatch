#pragma once

#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/bounded_spsc_ring.hpp"
#include "simplematch/matching/runtime/input_sequence.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <variant>

namespace simplematch::matching {

enum class MatchingRuntimeStep {
  kNoInput,
  kOwnershipDenied,
  kOutputBackpressured,
  kProcessed,
  kCoreRejected
};

struct RuntimeEventOutput {
  InputSequence input_sequence;
  std::size_t output_index;
  CoreEvent event;
};

struct RuntimeEndOfInput {
  InputSequence input_sequence;
  std::size_t output_count;
};

/** Transport-independent output produced by the single Matching writer. */
using RuntimeOutput = std::variant<RuntimeEventOutput, RuntimeEndOfInput>;

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

  [[nodiscard]] std::optional<InputSequence> submit(CoreCommand command);
  /** Reserves a sequence for an input that was accepted but needs no core execution. */
  [[nodiscard]] std::optional<InputSequence> reserve_input_sequence();
  [[nodiscard]] MatchingRuntimeStep process_one();
  [[nodiscard]] std::size_t input_size() const;
  [[nodiscard]] std::size_t output_size() const;
  [[nodiscard]] std::size_t maximum_output_events() const;
  [[nodiscard]] std::optional<RuntimeOutput> take_output();
  [[nodiscard]] BoundedSpscRing<RuntimeOutput> &output_ring();

private:
  struct RuntimeInput {
    InputSequence sequence;
    CoreCommand command;
  };

  std::shared_ptr<const PartitionOwnershipPermit> ownership_permit_;
  std::unique_ptr<DeterministicMatchingCore> core_;
  BoundedSpscRing<RuntimeInput> input_ring_;
  BoundedSpscRing<RuntimeOutput> output_ring_;
  InputSequence next_input_sequence_{};
};

} // namespace simplematch::matching
