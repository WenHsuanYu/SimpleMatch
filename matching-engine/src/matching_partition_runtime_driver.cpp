#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"

#include <chrono>
#include <thread>
#include <utility>

namespace simplematch::matching {

MatchingPartitionRuntimeDriver::MatchingPartitionRuntimeDriver(
    DirectPartitionKafkaConsumer &consumer,
    PartitionReplayCoordinator &coordinator,
    MatchingEventPublisher &publisher,
    std::unique_ptr<RuntimeCpuAffinity> cpu_affinity,
    RuntimeSupervisorOptions supervisor_options)
    : consumer_(consumer),
      input_driver_(consumer, coordinator),
      coordinator_(coordinator),
      publisher_(publisher),
      cpu_affinity_(cpu_affinity == nullptr
                        ? std::make_unique<NoopRuntimeCpuAffinity>()
                        : std::move(cpu_affinity)),
      supervisor_options_(std::move(supervisor_options)) {}

MatchingPartitionRuntimeDriver::~MatchingPartitionRuntimeDriver() {
  if (supervisor_ != nullptr) {
    supervisor_->shutdown(std::chrono::seconds(5));
  }
}

bool MatchingPartitionRuntimeDriver::start(const PvcBaselineMetadataStore *baseline_store) {
  if (!input_driver_.start()) {
    return false;
  }
  supervisor_ = std::make_unique<MatchingRuntimeSupervisor>(
      coordinator_.runtime(),
      coordinator_.ownership_permit(),
      std::move(cpu_affinity_),
      supervisor_options_);
  if (!supervisor_->start()) {
    return false;
  }
  if (!prepare_recovery(baseline_store)) {
    supervisor_->fail_closed("MATCHING_STARTUP_RECOVERY_PREFLIGHT_FAILED");
    return false;
  }
  if (!supervisor_->release_startup()) {
    return false;
  }
  if (!recover_before_live_processing()) {
    supervisor_->fail_closed("MATCHING_STARTUP_REPLAY_FAILED");
    return false;
  }
  return true;
}

MatchingPartitionDriverStep MatchingPartitionRuntimeDriver::run_once() {
  return run_threaded_once();
}

bool MatchingPartitionRuntimeDriver::shutdown(std::chrono::milliseconds deadline) {
  if (supervisor_ == nullptr) {
    return true;
  }
  const auto end = std::chrono::steady_clock::now() + deadline;
  while (coordinator_.status().pending_publication_count != 0 &&
         std::chrono::steady_clock::now() < end) {
    if (!drain_threaded_output_until_complete() || !publish_pending_events()) {
      break;
    }
    std::this_thread::yield();
  }
  const auto now = std::chrono::steady_clock::now();
  const auto remaining = now < end
                             ? std::chrono::duration_cast<std::chrono::milliseconds>(end - now)
                             : std::chrono::milliseconds::zero();
  supervisor_->shutdown(remaining);
  return coordinator_.status().pending_publication_count == 0 &&
         supervisor_->state() == MatchingRuntimeSupervisorState::kStopped;
}

MatchingPartitionDriverStep MatchingPartitionRuntimeDriver::run_threaded_once() {
  if (supervisor_ == nullptr ||
      supervisor_->state() == MatchingRuntimeSupervisorState::kFailedClosed) {
    if (supervisor_ != nullptr &&
        supervisor_->state() == MatchingRuntimeSupervisorState::kFailedClosed) {
      coordinator_.record_runtime_failure(supervisor_->failure_reason());
    }
    return coordinator_.ownership_permitted() ? MatchingPartitionDriverStep::kFailedClosed
                                              : MatchingPartitionDriverStep::kOwnershipDenied;
  }
  if (!publish_pending_events()) {
    return MatchingPartitionDriverStep::kPublicationUnavailable;
  }
  if (coordinator_.publication_retry_pending()) {
    return MatchingPartitionDriverStep::kBackpressured;
  }
  const auto result = input_driver_.poll_once_threaded();
  const auto mapped = map_result(result);
  if (mapped == MatchingPartitionDriverStep::kOwnershipDenied ||
      mapped == MatchingPartitionDriverStep::kFailedClosed ||
      mapped == MatchingPartitionDriverStep::kBackpressured) {
    return mapped;
  }
  if (!drain_threaded_output_until_complete()) {
    if (supervisor_->state() == MatchingRuntimeSupervisorState::kFailedClosed) {
      coordinator_.record_runtime_failure(supervisor_->failure_reason());
      return MatchingPartitionDriverStep::kFailedClosed;
    }
    return supervisor_->state() == MatchingRuntimeSupervisorState::kFailedClosed
               ? MatchingPartitionDriverStep::kFailedClosed
               : MatchingPartitionDriverStep::kBackpressured;
  }
  if (!publish_pending_events()) {
    return MatchingPartitionDriverStep::kPublicationUnavailable;
  }
  static_cast<void>(input_driver_.commit_completed_synchronously());
  return result == PartitionReplayResult::kAccepted ? MatchingPartitionDriverStep::kProcessed
                                                    : mapped;
}

bool MatchingPartitionRuntimeDriver::drain_threaded_output_until_complete() {
  const auto deadline =
      std::chrono::steady_clock::now() + supervisor_options_.output_drain_timeout;
  while (std::chrono::steady_clock::now() < deadline) {
    const auto result = coordinator_.drain_threaded_outputs();
    if (result == PartitionReplayResult::kAccepted ||
        result == PartitionReplayResult::kDuplicate) {
      return true;
    }
    if (result == PartitionReplayResult::kOwnershipDenied ||
        result == PartitionReplayResult::kFailedClosed ||
        result == PartitionReplayResult::kIdentityMismatch ||
        result == PartitionReplayResult::kMissingRetainedOpenBarrier ||
        result == PartitionReplayResult::kRetentionInsufficient) {
      return false;
    }
    std::this_thread::yield();
  }
  return false;
}

bool MatchingPartitionRuntimeDriver::prepare_recovery(
    const PvcBaselineMetadataStore *baseline_store) {
  recovery_plan_.reset();
  if (baseline_store == nullptr) {
    return true;
  }
  const DirectKafkaPartitionOffsets offsets = consumer_.offsets();
  const auto baseline = baseline_store->load();
  if (!offsets.committed_offset.has_value() || *offsets.committed_offset <= 0) {
    return true;
  }
  if (offsets.earliest_retained_offset < 0 || offsets.end_offset < offsets.earliest_retained_offset ||
      *offsets.committed_offset > offsets.end_offset) {
    return false;
  }
  const RetainedRecordBatchReader reader =
      [this](std::int64_t first_offset, std::int64_t end_offset, std::size_t maximum_records) {
        return consumer_.read_retained_batch(first_offset, end_offset, maximum_records);
      };
  if (baseline.has_value()) {
    if (!coordinator_.validate_recovery_baseline(
            *baseline, offsets.earliest_retained_offset, *offsets.committed_offset)) {
      return false;
    }
    recovery_plan_ = RecoveryPlan{*baseline, offsets.earliest_retained_offset};
    return true;
  }

  const std::int64_t committed_last_offset = *offsets.committed_offset - 1;
  if (committed_last_offset < offsets.earliest_retained_offset) {
    return false;
  }
  const auto discovered = coordinator_.discover_retained_recovery_baseline(
      offsets.earliest_retained_offset, committed_last_offset, reader);
  if (!discovered.has_value() ||
      !coordinator_.validate_recovery_baseline(
          *discovered, offsets.earliest_retained_offset, *offsets.committed_offset)) {
    return false;
  }
  recovery_plan_ = RecoveryPlan{*discovered, offsets.earliest_retained_offset};
  return true;
}

bool MatchingPartitionRuntimeDriver::recover_before_live_processing() {
  if (!recovery_plan_.has_value()) {
    return true;
  }
  const RetainedRecordBatchReader reader =
      [this](std::int64_t first_offset, std::int64_t end_offset, std::size_t maximum_records) {
        return consumer_.read_retained_batch(first_offset, end_offset, maximum_records);
      };
  const ThreadedRecoveryStep execution_step = [this] {
    return drain_threaded_output_until_complete() ? PartitionReplayResult::kAccepted
                                                   : PartitionReplayResult::kFailedClosed;
  };
  if (coordinator_.recover_threaded(
          recovery_plan_->baseline,
          recovery_plan_->earliest_retained_offset,
          reader,
          execution_step) != PartitionReplayResult::kRecovered) {
      return false;
  }
  consumer_.seek(recovery_plan_->baseline.committed_offset + 1);
  return true;
}

bool MatchingPartitionRuntimeDriver::publish_pending_events() {
  if (publisher_.supports_async()) {
    return service_async_publications() && submit_async_publications();
  }
  while (const auto publication = coordinator_.next_unacknowledged_publication()) {
    if (!publisher_.publish(*publication)) {
      return false;
    }
    const auto result = coordinator_.acknowledge_published(
        publication->source_input_offset, publication->output_index);
    if (result == PartitionReplayResult::kOwnershipDenied ||
        result == PartitionReplayResult::kFailedClosed ||
        result == PartitionReplayResult::kOutputBackpressured) {
      return false;
    }
  }
  return true;
}

bool MatchingPartitionRuntimeDriver::service_async_publications() {
  publisher_.service();
  while (const auto completion = publisher_.next_completion()) {
    const auto result = coordinator_.acknowledge_published(
        completion->publication_id, completion->delivery);
    if (result == PartitionReplayResult::kFailedClosed ||
        result == PartitionReplayResult::kOwnershipDenied) {
      return false;
    }
  }
  return true;
}

bool MatchingPartitionRuntimeDriver::submit_async_publications() {
  while (const auto publication = coordinator_.next_unacknowledged_publication()) {
    const auto publication_id = coordinator_.publication_id_for(
        publication->source_input_offset, publication->output_index);
    if (!publication_id.has_value()) {
      return false;
    }
    const auto submission = publisher_.submit_async(*publication, *publication_id);
    if (submission == MatchingPublicationSubmitResult::kFailedClosed) {
      supervisor_->fail_closed("MATCHING_PUBLICATION_SUBMIT_FATAL");
      return false;
    }
    if (submission != MatchingPublicationSubmitResult::kSubmitted) {
      return false;
    }
    const auto marked = coordinator_.mark_publication_submitted(
        publication->source_input_offset, publication->output_index, *publication_id);
    if (marked == PartitionReplayResult::kFailedClosed ||
        marked == PartitionReplayResult::kOwnershipDenied) {
      return false;
    }
  }
  return true;
}

MatchingPartitionDriverStep MatchingPartitionRuntimeDriver::map_result(
    PartitionReplayResult result) const {
  switch (result) {
    case PartitionReplayResult::kOwnershipDenied:
      return MatchingPartitionDriverStep::kOwnershipDenied;
    case PartitionReplayResult::kInputBackpressured:
    case PartitionReplayResult::kOutputBackpressured:
      return MatchingPartitionDriverStep::kBackpressured;
    case PartitionReplayResult::kFailedClosed:
    case PartitionReplayResult::kIdentityMismatch:
    case PartitionReplayResult::kMissingRetainedOpenBarrier:
    case PartitionReplayResult::kRetentionInsufficient:
      return MatchingPartitionDriverStep::kFailedClosed;
    case PartitionReplayResult::kAccepted:
    case PartitionReplayResult::kDuplicate:
    case PartitionReplayResult::kRecovered:
      return MatchingPartitionDriverStep::kProcessed;
  }
  return MatchingPartitionDriverStep::kFailedClosed;
}

} // namespace simplematch::matching
