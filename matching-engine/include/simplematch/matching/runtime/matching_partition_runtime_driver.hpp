#pragma once

#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"

namespace simplematch::matching {

/** Infrastructure seam for publishing one encoded Matching Event outside the core. */
class MatchingEventPublisher {
public:
  virtual ~MatchingEventPublisher() = default;

  /** Publishes one event and returns false without acknowledging it when the broker is unavailable. */
  [[nodiscard]] virtual bool publish(const MatchingEventRecord &record) = 0;
};

/** Result of one bounded poll, publish, and contiguous-commit driver step. */
enum class MatchingPartitionDriverStep {
  kIdle,
  kProcessed,
  kBackpressured,
  kOwnershipDenied,
  kPublicationUnavailable,
  kFailedClosed
};

/**
 * Coordinates the infrastructure adapters around one partition replay coordinator.
 *
 * <p>The driver acknowledges an output only after the publisher reports success and commits an
 * input offset only after the coordinator exposes a contiguous completed watermark. The matching
 * core is never given a publisher, consumer, or commit callback.</p>
 */
class MatchingPartitionRuntimeDriver {
public:
  MatchingPartitionRuntimeDriver(
      DirectPartitionKafkaConsumer &consumer,
      PartitionReplayCoordinator &coordinator,
      MatchingEventPublisher &publisher);

  /** Assigns the fixed partition after ownership has been confirmed. */
  [[nodiscard]] bool start(const PvcBaselineMetadataStore *baseline_store = nullptr);

  /** Executes one bounded poll, output-publication, and offset-commit cycle. */
  [[nodiscard]] MatchingPartitionDriverStep run_once();

private:
  [[nodiscard]] bool publish_pending_events();
  [[nodiscard]] bool recover_before_live_processing(
      const PvcBaselineMetadataStore *baseline_store);
  [[nodiscard]] MatchingPartitionDriverStep map_result(PartitionReplayResult result) const;

  DirectPartitionKafkaConsumer &consumer_;
  DirectPartitionRuntimeDriver input_driver_;
  PartitionReplayCoordinator &coordinator_;
  MatchingEventPublisher &publisher_;
};

} // namespace simplematch::matching
