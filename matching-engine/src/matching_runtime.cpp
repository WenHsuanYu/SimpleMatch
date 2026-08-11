#include "simplematch/matching/runtime/matching_runtime.hpp"

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
}

bool MatchingRuntime::submit(CoreCommand command) {
  if (!ownership_permit_->allows_processing()) {
    return false;
  }
  return input_ring_.try_push(std::move(command));
}

MatchingRuntimeStep MatchingRuntime::process_one() {
  if (!ownership_permit_->allows_processing()) {
    return MatchingRuntimeStep::kOwnershipDenied;
  }
  if (input_ring_.size() == 0) {
    return MatchingRuntimeStep::kNoInput;
  }
  if (output_ring_.available_to_write() < core_->maximum_output_events()) {
    return MatchingRuntimeStep::kOutputBackpressured;
  }
  auto command = input_ring_.try_pop();
  if (!command.has_value()) {
    return MatchingRuntimeStep::kNoInput;
  }
  if (core_->process(*command) != MatchingProcessResult::kApplied) {
    return MatchingRuntimeStep::kCoreRejected;
  }
  for (const CoreEvent &event : core_->events()) {
    if (!output_ring_.try_push(event)) {
      throw std::logic_error("output ring capacity changed while the single writer was processing");
    }
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

std::optional<CoreEvent> MatchingRuntime::take_output() {
  return output_ring_.try_pop();
}

BoundedSpscRing<CoreEvent> &MatchingRuntime::output_ring() {
  return output_ring_;
}

} // namespace simplematch::matching
