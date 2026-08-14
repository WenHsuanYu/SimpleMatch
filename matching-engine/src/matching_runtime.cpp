#include "simplematch/matching/runtime/matching_runtime.hpp"

#include <limits>
#include <stdexcept>
#include <utility>

namespace simplematch::matching {

MatchingRuntime::MatchingRuntime(
    std::unique_ptr<DeterministicMatchingCore> core,
    std::size_t input_capacity,
    std::size_t output_capacity,
    std::shared_ptr<const PartitionOwnershipPermit> ownership_permit)
    : ownership_permit_(std::move(ownership_permit)),
      core_(std::move(core)),
      input_ring_(input_capacity),
      output_ring_(output_capacity) {
  if (core_ == nullptr || ownership_permit_ == nullptr) {
    throw std::invalid_argument("matching runtime requires one core and ownership permit");
  }
  if (output_ring_.capacity() < core_->maximum_output_events() + 1) {
    throw std::invalid_argument(
        "matching output ring must hold one worst-case event burst and its end marker");
  }
}

std::optional<InputSequence> MatchingRuntime::submit(CoreCommand command) {
  if (!ownership_permit_->allows_processing()) {
    return std::nullopt;
  }
  if (next_input_sequence_ == std::numeric_limits<InputSequence>::max()) {
    return std::nullopt;
  }
  const InputSequence sequence = next_input_sequence_;
  if (!input_ring_.try_push(RuntimeInput{sequence, std::move(command)})) {
    return std::nullopt;
  }
  ++next_input_sequence_;
  return sequence;
}

std::optional<InputSequence> MatchingRuntime::reserve_input_sequence() {
  if (!ownership_permit_->allows_processing() ||
      next_input_sequence_ == std::numeric_limits<InputSequence>::max()) {
    return std::nullopt;
  }
  return next_input_sequence_++;
}

MatchingRuntimeStep MatchingRuntime::process_one() {
  if (!ownership_permit_->allows_processing()) {
    return MatchingRuntimeStep::kOwnershipDenied;
  }
  if (input_ring_.size() == 0) {
    return MatchingRuntimeStep::kNoInput;
  }
  const std::size_t required_output_slots = core_->maximum_output_events() + 1;
  if (output_ring_.available_to_write() < required_output_slots) {
    return MatchingRuntimeStep::kOutputBackpressured;
  }
  auto command = input_ring_.try_pop();
  if (!command.has_value()) {
    return MatchingRuntimeStep::kNoInput;
  }
  if (core_->process(command->command) != MatchingProcessResult::kApplied) {
    return MatchingRuntimeStep::kCoreRejected;
  }
  std::size_t output_index = 0;
  for (const CoreEvent &event : core_->events()) {
    if (!output_ring_.try_push(RuntimeEventOutput{
            command->sequence, output_index, event})) {
      throw std::logic_error("output ring capacity changed while the single writer was processing");
    }
    ++output_index;
  }
  if (!output_ring_.try_push(RuntimeEndOfInput{command->sequence, output_index})) {
    throw std::logic_error("output ring lost its reserved end-of-input slot");
  }
  return MatchingRuntimeStep::kProcessed;
}

std::size_t MatchingRuntime::input_size() const {
  return input_ring_.size();
}

std::size_t MatchingRuntime::output_size() const {
  return output_ring_.size();
}

std::size_t MatchingRuntime::maximum_output_events() const {
  return core_->maximum_output_events();
}

std::optional<RuntimeOutput> MatchingRuntime::take_output() {
  return output_ring_.try_pop();
}

BoundedSpscRing<RuntimeOutput> &MatchingRuntime::output_ring() {
  return output_ring_;
}

} // namespace simplematch::matching
