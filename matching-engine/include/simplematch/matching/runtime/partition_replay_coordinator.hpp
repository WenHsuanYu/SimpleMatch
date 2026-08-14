#pragma once

#include "simplematch/matching/ingress/matching_command_decoder.hpp"
#include "simplematch/matching/runtime/matching_event_encoder.hpp"
#include "simplematch/matching/runtime/matching_runtime.hpp"
#include "simplematch/matching/runtime/input_offset_ledger.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace simplematch::matching {

enum class MatchingPublicationDelivery {
  kPersisted,
  kNotPersisted,
  kPossiblyPersisted,
  kFatal
};

/** The one Kafka partition configured for a matching-N process; no consumer group is involved. */
struct DirectKafkaPartitionAssignment {
  std::string topic;
  std::int32_t partition;

  bool operator==(const DirectKafkaPartitionAssignment &) const = default;
};

/** One raw record received from the assigned matching.commands partition. */
struct AssignedCommandRecord {
  DirectKafkaPartitionAssignment assignment;
  std::int64_t offset;
  std::string key;
  std::string value;
};

/**
 * Reads one bounded retained batch from the Kafka recovery adapter.
 *
 * <p>The reader returns records in ascending, contiguous offset order, returns no more than the
 * requested maximum, and returns an empty batch only when the requested range is empty or the
 * retention source cannot provide the next record.</p>
 */
using RetainedRecordBatchReader = std::function<std::vector<AssignedCommandRecord>(
    std::int64_t first_offset, std::int64_t end_offset, std::size_t maximum_records)>;

/** Kafka offsets needed to decide whether a retained replay can complete safely. */
struct DirectKafkaPartitionOffsets {
  std::int64_t earliest_retained_offset;
  std::int64_t end_offset;
  std::optional<std::int64_t> committed_offset;
};

/** Infrastructure boundary implemented by a Kafka adapter with direct assign(), never subscribe(). */
class DirectPartitionKafkaConsumer {
public:
  virtual ~DirectPartitionKafkaConsumer() = default;

  virtual void assign(const DirectKafkaPartitionAssignment &assignment) = 0;
  [[nodiscard]] virtual std::optional<AssignedCommandRecord> poll() = 0;
  virtual void commit_synchronously(std::int64_t next_offset) = 0;

  /** Returns retained low/high offsets and the consumer group's committed next offset. */
  [[nodiscard]] virtual DirectKafkaPartitionOffsets offsets() {
    throw std::logic_error("Kafka consumer does not expose partition offsets");
  }

  /** Reads at most maximum_records from [first_offset, end_offset). */
  [[nodiscard]] virtual std::vector<AssignedCommandRecord> read_retained_batch(
      std::int64_t first_offset, std::int64_t end_offset, std::size_t maximum_records) {
    static_cast<void>(first_offset);
    static_cast<void>(end_offset);
    static_cast<void>(maximum_records);
    throw std::logic_error("Kafka consumer does not expose retained replay");
  }

  /** Moves the assigned consumer to an absolute offset after recovery. */
  virtual void seek(std::int64_t next_offset) {
    static_cast<void>(next_offset);
    throw std::logic_error("Kafka consumer does not expose seeking");
  }
};

/** Session-pinned identities verified by the Open Barrier and every command. */
struct PinnedMatchingIdentity {
  std::string artifact_identity;
  std::string trading_session_id;
  std::string routing_algorithm_version;
  std::string matching_image_digest;
  std::int32_t command_schema_version{1};
  std::int32_t event_schema_version{1};
  std::int32_t event_identity_version{1};

  bool operator==(const PinnedMatchingIdentity &) const = default;
};

/** Bounded PVC acceleration metadata; Kafka commands remain the recovery authority. */
struct PartitionBaselineMetadata {
  DirectKafkaPartitionAssignment assignment;
  PinnedMatchingIdentity identity;
  std::int64_t open_barrier_offset;
  std::int64_t committed_offset;

  bool operator==(const PartitionBaselineMetadata &) const = default;
};

/** Persists only replay coordinates and pins; it never contains a Matching order-book snapshot. */
class PvcBaselineMetadataStore {
public:
  explicit PvcBaselineMetadataStore(std::string file_path);

  [[nodiscard]] std::optional<PartitionBaselineMetadata> load() const;
  void save(const PartitionBaselineMetadata &metadata) const;

private:
  std::string file_path_;
};

enum class PartitionSessionState { kAwaitingOpen, kOpen, kClosed, kFailedClosed };

enum class PartitionReplayResult {
  kAccepted,
  kDuplicate,
  kOwnershipDenied,
  kInputBackpressured,
  kOutputBackpressured,
  kRecovered,
  kMissingRetainedOpenBarrier,
  kRetentionInsufficient,
  kIdentityMismatch,
  kFailedClosed
};

/** Executes one already-submitted command through the external single-writer supervisor. */
using ThreadedRecoveryStep = std::function<PartitionReplayResult()>;

/** Readiness-relevant progress exported without Kafka client implementation types. */
struct PartitionReplayStatus {
  PartitionSessionState state;
  PartitionOwnershipStatus ownership;
  std::optional<std::int64_t> highest_contiguous_completed_offset;
  std::optional<std::int64_t> next_commit_offset;
  std::size_t pending_input_count;
  std::size_t pending_publication_count;
  std::string reason;
};

/**
 * Rebuilds one partition from its retained Open Barrier and tracks publication-before-commit.
 *
 * <p>This adapter-owned coordinator performs no Kafka I/O itself. Its caller direct-assigns the
 * one configured partition, calls {@link ingest}, publishes {@link next_unacknowledged_publication},
 * acknowledges producer results, and commits only {@link next_commit_offset}.</p>
 */
class PartitionReplayCoordinator {
public:
  PartitionReplayCoordinator(
      DirectKafkaPartitionAssignment assignment,
      PinnedMatchingIdentity identity,
      std::shared_ptr<const PartitionOwnershipPermit> ownership_permit,
      std::unique_ptr<DeterministicMatchingCore> core,
      std::size_t input_capacity,
      std::size_t output_capacity,
      std::size_t maximum_distinct_commands,
      std::size_t maximum_pending_publications);

  [[nodiscard]] const DirectKafkaPartitionAssignment &assignment() const;
  /** Exposes the transport-free runtime to the single-writer supervisor. */
  [[nodiscard]] MatchingRuntime &runtime();
  /** Exposes the immutable ownership adapter to infrastructure lifecycle components. */
  [[nodiscard]] std::shared_ptr<const PartitionOwnershipPermit> ownership_permit() const;
  [[nodiscard]] bool ownership_permitted() const;
  [[nodiscard]] PartitionReplayResult ingest(const AssignedCommandRecord &record);
  /** Ingress-only path; the writer supervisor processes the submitted command later. */
  [[nodiscard]] PartitionReplayResult ingest_for_threaded_writer(
      const AssignedCommandRecord &record, bool suppress_publication = false);
  [[nodiscard]] PartitionReplayResult continue_processing();
  /** Publisher-side path that drains output frames after the writer has processed one input. */
  [[nodiscard]] PartitionReplayResult drain_threaded_outputs();
  [[nodiscard]] std::optional<MatchingEventRecord> next_unacknowledged_publication() const;
  /** Returns true while an ambiguous or non-persisted publication must be retried first. */
  [[nodiscard]] bool publication_retry_pending() const noexcept;
  [[nodiscard]] std::optional<std::uint64_t> publication_id_for(
      std::int64_t input_offset, std::int32_t output_index) const;
  [[nodiscard]] PartitionReplayResult mark_publication_submitted(
      std::int64_t input_offset, std::int32_t output_index, std::uint64_t publication_id);
  [[nodiscard]] PartitionReplayResult acknowledge_published(
      std::int64_t input_offset, std::int32_t output_index);
  [[nodiscard]] PartitionReplayResult acknowledge_published(
      std::uint64_t publication_id, MatchingPublicationDelivery delivery);
  [[nodiscard]] std::optional<std::int64_t> next_commit_offset() const;
  [[nodiscard]] bool acknowledge_commit(std::int64_t next_offset);
  [[nodiscard]] std::optional<PartitionBaselineMetadata> baseline_metadata() const;
  [[nodiscard]] PartitionReplayStatus status() const;

  [[nodiscard]] PartitionReplayResult recover(
      const PartitionBaselineMetadata &baseline,
      std::int64_t earliest_retained_offset,
      std::span<const AssignedCommandRecord> retained_records);
  /** Rebuilds from a baseline while retaining only one bounded Kafka batch at a time. */
  [[nodiscard]] PartitionReplayResult recover(
      const PartitionBaselineMetadata &baseline,
      std::int64_t earliest_retained_offset,
      const RetainedRecordBatchReader &reader);
  /** Replays through the caller-owned pinned writer instead of mutating the core inline. */
  [[nodiscard]] PartitionReplayResult recover_threaded(
      const PartitionBaselineMetadata &baseline,
      std::int64_t earliest_retained_offset,
      const RetainedRecordBatchReader &reader,
      const ThreadedRecoveryStep &execution_step);
  [[nodiscard]] PartitionReplayResult recover_from_retained_records(
      std::int64_t earliest_retained_offset,
      std::int64_t committed_offset,
      std::span<const AssignedCommandRecord> retained_records);
  /** Scans for the latest valid Open Barrier and replays it using bounded Kafka batches. */
  [[nodiscard]] PartitionReplayResult recover_from_retained_batches(
      std::int64_t earliest_retained_offset,
      std::int64_t committed_offset,
      const RetainedRecordBatchReader &reader);
  /** Scans for an Open Barrier and replays through the caller-owned pinned writer. */
  [[nodiscard]] PartitionReplayResult recover_from_retained_batches_threaded(
      std::int64_t earliest_retained_offset,
      std::int64_t committed_offset,
      const RetainedRecordBatchReader &reader,
      const ThreadedRecoveryStep &execution_step);

  /** Validates a stored baseline against the current assignment and Kafka commit boundary. */
  [[nodiscard]] bool validate_recovery_baseline(
      const PartitionBaselineMetadata &baseline,
      std::int64_t earliest_retained_offset,
      std::int64_t committed_next_offset) const;

  /** Finds a valid retained Open Barrier without mutating runtime state. */
  [[nodiscard]] std::optional<PartitionBaselineMetadata>
  discover_retained_recovery_baseline(
      std::int64_t earliest_retained_offset,
      std::int64_t committed_offset,
      const RetainedRecordBatchReader &reader) const;

private:
  struct CommandIdentity {
    std::array<std::uint8_t, 16> bytes;

    auto operator<=>(const CommandIdentity &) const = default;
  };

  struct PendingInput {
    InputSequence input_sequence;
    MatchingCommandContext context;
    std::size_t expected_output_count{};
    std::set<std::int32_t> acknowledged_output_indices;
    bool processed{};
  };

  struct PendingPublication {
    MatchingEventRecord record;
    bool acknowledged{};
    bool submitted{};
    std::uint64_t publication_id{};
  };

  struct ActiveCommand {
    InputSequence input_sequence;
    std::int64_t input_offset;
    MatchingCommandContext context;
    CoreCommandType type;
    bool suppress_publication;
    std::size_t output_count{};
  };

  [[nodiscard]] PartitionReplayResult ingest_internal(
      const AssignedCommandRecord &record, bool suppress_publication, bool process_immediately);
  [[nodiscard]] PartitionReplayResult drive_active_command();
  [[nodiscard]] PartitionReplayResult drain_active_command_outputs();
  [[nodiscard]] bool accepts_context(const MatchingCommandContext &context) const;
  [[nodiscard]] bool accepts_open_barrier(const MatchingCommandDecodeResult &decoded) const;
  [[nodiscard]] bool accepts_next_offset(std::int64_t offset);
  void mark_completed(std::int64_t input_offset);
  void release_completed_inputs();
  [[nodiscard]] PartitionReplayResult recover_from_batches(
      const PartitionBaselineMetadata &baseline,
      const RetainedRecordBatchReader &reader,
      bool process_immediately,
      const ThreadedRecoveryStep *execution_step);
  [[nodiscard]] PartitionReplayResult fail_retention(std::string reason);
  [[nodiscard]] PartitionReplayResult acknowledge_publication(PendingPublication &publication);
  [[nodiscard]] PartitionReplayResult fail_closed(std::string reason);

  DirectKafkaPartitionAssignment assignment_;
  PinnedMatchingIdentity identity_;
  std::shared_ptr<const PartitionOwnershipPermit> ownership_permit_;
  MatchingCommandDecoder decoder_;
  MatchingEventEncoder event_encoder_;
  MatchingRuntime runtime_;
  InputOffsetLedger input_ledger_;
  std::size_t maximum_distinct_commands_;
  std::size_t maximum_pending_publications_;
  PartitionSessionState state_{PartitionSessionState::kAwaitingOpen};
  std::string failure_reason_;
  std::optional<std::int64_t> expected_next_input_offset_;
  std::optional<std::int64_t> committed_offset_;
  std::optional<std::int64_t> open_barrier_offset_;
  std::map<CommandIdentity, std::array<char, 65>> command_fingerprints_;
  std::map<std::int64_t, std::unique_ptr<PendingInput>> pending_inputs_;
  std::deque<PendingPublication> pending_publications_;
  std::optional<ActiveCommand> active_command_;
  std::optional<std::uint64_t> retry_publication_id_;
  std::uint64_t next_publication_id_{1};
};

/** Minimal adapter loop that proves direct assignment and synchronous contiguous commits. */
class DirectPartitionRuntimeDriver {
public:
  DirectPartitionRuntimeDriver(
      DirectPartitionKafkaConsumer &consumer, PartitionReplayCoordinator &coordinator);

  [[nodiscard]] bool start();
  [[nodiscard]] PartitionReplayResult poll_once();
  /** Polls ingress without executing the core; the writer supervisor owns execution. */
  [[nodiscard]] PartitionReplayResult poll_once_threaded();
  [[nodiscard]] bool commit_completed_synchronously();

private:
  DirectPartitionKafkaConsumer &consumer_;
  PartitionReplayCoordinator &coordinator_;
};

} // namespace simplematch::matching
