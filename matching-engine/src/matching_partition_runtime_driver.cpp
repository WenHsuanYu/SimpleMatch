#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"

namespace simplematch::matching {

MatchingPartitionRuntimeDriver::MatchingPartitionRuntimeDriver(
    DirectPartitionKafkaConsumer &consumer,
    PartitionReplayCoordinator &coordinator,
    MatchingEventPublisher &publisher)
    : consumer_(consumer),
      input_driver_(consumer, coordinator),
      coordinator_(coordinator),
      publisher_(publisher) {}

bool MatchingPartitionRuntimeDriver::start(const PvcBaselineMetadataStore *baseline_store) {
  if (!input_driver_.start()) {
    return false;
  }
  return recover_before_live_processing(baseline_store);
}

MatchingPartitionDriverStep MatchingPartitionRuntimeDriver::run_once() {
  if (!publish_pending_events()) {
    return MatchingPartitionDriverStep::kPublicationUnavailable;
  }
  const auto result = input_driver_.poll_once();
  const auto mapped = map_result(result);
  if (mapped == MatchingPartitionDriverStep::kOwnershipDenied ||
      mapped == MatchingPartitionDriverStep::kFailedClosed ||
      mapped == MatchingPartitionDriverStep::kBackpressured) {
    return mapped;
  }
  if (!publish_pending_events()) {
    return MatchingPartitionDriverStep::kPublicationUnavailable;
  }
  static_cast<void>(input_driver_.commit_completed_synchronously());
  return result == PartitionReplayResult::kAccepted ? MatchingPartitionDriverStep::kProcessed
                                                    : mapped;
}

bool MatchingPartitionRuntimeDriver::recover_before_live_processing(
    const PvcBaselineMetadataStore *baseline_store) {
  if (baseline_store == nullptr) {
    return true;
  }
  const DirectKafkaPartitionOffsets offsets = consumer_.offsets();
  const auto baseline = baseline_store->load();
  if (baseline.has_value()) {
    if (!offsets.committed_offset.has_value() ||
        baseline->committed_offset >= *offsets.committed_offset ||
        baseline->committed_offset < baseline->open_barrier_offset ||
        baseline->committed_offset + 1 > offsets.end_offset ||
        baseline->open_barrier_offset < offsets.earliest_retained_offset) {
      return false;
    }
    const auto retained = consumer_.read_retained(
        baseline->open_barrier_offset, baseline->committed_offset + 1);
    if (coordinator_.recover(
            *baseline, offsets.earliest_retained_offset, retained) !=
        PartitionReplayResult::kRecovered) {
      return false;
    }
    consumer_.seek(baseline->committed_offset + 1);
    return true;
  }

  if (!offsets.committed_offset.has_value() || *offsets.committed_offset <= 0) {
    return true;
  }
  const std::int64_t committed_last_offset = *offsets.committed_offset - 1;
  if (committed_last_offset < offsets.earliest_retained_offset ||
      *offsets.committed_offset > offsets.end_offset) {
    return false;
  }
  const auto retained = consumer_.read_retained(
      offsets.earliest_retained_offset, *offsets.committed_offset);
  const auto result = coordinator_.recover_from_retained_records(
      offsets.earliest_retained_offset, committed_last_offset, retained);
  if (result != PartitionReplayResult::kRecovered) {
    return false;
  }
  consumer_.seek(*offsets.committed_offset);
  return true;
}

bool MatchingPartitionRuntimeDriver::publish_pending_events() {
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
