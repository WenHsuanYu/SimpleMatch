#pragma once

#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"
#include "simplematch/matching/runtime/matching_runtime_supervisor.hpp"

#include <cstdint>
#include <chrono>
#include <memory>
#include <optional>

namespace simplematch::matching {

enum class MatchingPublicationSubmitResult {
  kSubmitted,
  kBackpressured,
  kUnavailable,
  kFailedClosed
};

struct MatchingPublicationCompletion {
  std::uint64_t publication_id;
  MatchingPublicationDelivery delivery;
};

/** Infrastructure seam for publishing one encoded Matching Event outside the core. */
class MatchingEventPublisher {
public:
  virtual ~MatchingEventPublisher() = default;

  /** Publishes one event and returns false without acknowledging it when the broker is unavailable. */
  [[nodiscard]] virtual bool publish(const MatchingEventRecord &record) = 0;

  /** Returns true when output slots remain in flight until a delivery report arrives. */
  [[nodiscard]] virtual bool supports_async() const noexcept { return false; }

  /** Submits one bounded asynchronous publication with an application correlation id. */
  [[nodiscard]] virtual MatchingPublicationSubmitResult submit_async(
      const MatchingEventRecord &record, std::uint64_t publication_id) {
    static_cast<void>(publication_id);
    return publish(record) ? MatchingPublicationSubmitResult::kSubmitted
                           : MatchingPublicationSubmitResult::kUnavailable;
  }

  /** Services delivery callbacks; the coordinator calls this continuously. */
  virtual void service() {}

  /** Returns one delivery report from the publisher's bounded completion queue. */
  [[nodiscard]] virtual std::optional<MatchingPublicationCompletion> next_completion() {
    return std::nullopt;
  }
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
      MatchingEventPublisher &publisher,
      std::unique_ptr<RuntimeCpuAffinity> cpu_affinity = nullptr,
      RuntimeSupervisorOptions supervisor_options = {});

  ~MatchingPartitionRuntimeDriver();

  /** Assigns the fixed partition after ownership has been confirmed. */
  [[nodiscard]] bool start(const PvcBaselineMetadataStore *baseline_store = nullptr);

  /** Executes one bounded poll, output-publication, and offset-commit cycle. */
  [[nodiscard]] MatchingPartitionDriverStep run_once();

  /** Stops ingress, drains bounded publication state when possible, and joins the writer. */
  [[nodiscard]] bool shutdown(std::chrono::milliseconds deadline);

private:
  [[nodiscard]] bool publish_pending_events();
  [[nodiscard]] bool service_async_publications();
  [[nodiscard]] bool submit_async_publications();
  [[nodiscard]] bool prepare_recovery(const PvcBaselineMetadataStore *baseline_store);
  [[nodiscard]] bool recover_before_live_processing();
  [[nodiscard]] MatchingPartitionDriverStep run_threaded_once();
  [[nodiscard]] bool drain_threaded_output_until_complete();
  [[nodiscard]] MatchingPartitionDriverStep map_result(PartitionReplayResult result) const;

  struct RecoveryPlan {
    PartitionBaselineMetadata baseline;
    std::int64_t earliest_retained_offset;
  };

  DirectPartitionKafkaConsumer &consumer_;
  DirectPartitionRuntimeDriver input_driver_;
  PartitionReplayCoordinator &coordinator_;
  MatchingEventPublisher &publisher_;
  std::unique_ptr<RuntimeCpuAffinity> cpu_affinity_;
  RuntimeSupervisorOptions supervisor_options_;
  std::unique_ptr<MatchingRuntimeSupervisor> supervisor_;
  std::optional<RecoveryPlan> recovery_plan_;
};

} // namespace simplematch::matching
