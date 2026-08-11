#pragma once

#include "simplematch/matching/ingress/matching_command_decoder.hpp"
#include "simplematch/matching/runtime/matching_event_encoder.hpp"
#include "simplematch/matching/runtime/matching_runtime.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <span>
#include <string>

namespace simplematch::matching {

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

/** Infrastructure boundary implemented by a Kafka adapter with direct assign(), never subscribe(). */
class DirectPartitionKafkaConsumer {
public:
  virtual ~DirectPartitionKafkaConsumer() = default;

  virtual void assign(const DirectKafkaPartitionAssignment &assignment) = 0;
  [[nodiscard]] virtual std::optional<AssignedCommandRecord> poll() = 0;
  virtual void commit_synchronously(std::int64_t next_offset) = 0;
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
  [[nodiscard]] bool ownership_permitted() const;
  [[nodiscard]] PartitionReplayResult ingest(const AssignedCommandRecord &record);
  [[nodiscard]] PartitionReplayResult continue_processing();
  [[nodiscard]] std::optional<MatchingEventRecord> next_unacknowledged_publication() const;
  [[nodiscard]] PartitionReplayResult acknowledge_published(
      std::int64_t input_offset, std::int32_t output_index);
  [[nodiscard]] std::optional<std::int64_t> next_commit_offset() const;
  [[nodiscard]] bool acknowledge_commit(std::int64_t next_offset);
  [[nodiscard]] std::optional<PartitionBaselineMetadata> baseline_metadata() const;
  [[nodiscard]] PartitionReplayStatus status() const;

  [[nodiscard]] PartitionReplayResult recover(
      const PartitionBaselineMetadata &baseline,
      std::int64_t earliest_retained_offset,
      std::span<const AssignedCommandRecord> retained_records);
  [[nodiscard]] PartitionReplayResult recover_from_retained_records(
      std::int64_t earliest_retained_offset,
      std::int64_t committed_offset,
      std::span<const AssignedCommandRecord> retained_records);

private:
  struct CommandIdentity {
    std::array<std::uint8_t, 16> bytes;

    auto operator<=>(const CommandIdentity &) const = default;
  };

  struct PendingInput {
    MatchingCommandContext context;
    std::size_t expected_output_count{};
    std::set<std::int32_t> acknowledged_output_indices;
    bool processed{};
  };

  struct PendingPublication {
    MatchingEventRecord record;
    bool acknowledged{};
  };

  struct ActiveCommand {
    std::int64_t input_offset;
    MatchingCommandContext context;
    CoreCommandType type;
    bool suppress_publication;
  };

  [[nodiscard]] PartitionReplayResult ingest_internal(
      const AssignedCommandRecord &record, bool suppress_publication);
  [[nodiscard]] PartitionReplayResult drive_active_command();
  [[nodiscard]] bool accepts_context(const MatchingCommandContext &context) const;
  [[nodiscard]] bool accepts_open_barrier(const MatchingCommandDecodeResult &decoded) const;
  [[nodiscard]] bool accepts_next_offset(std::int64_t offset);
  void mark_completed(std::int64_t input_offset);
  [[nodiscard]] PartitionReplayResult fail_closed(std::string reason);

  DirectKafkaPartitionAssignment assignment_;
  PinnedMatchingIdentity identity_;
  std::shared_ptr<const PartitionOwnershipPermit> ownership_permit_;
  MatchingCommandDecoder decoder_;
  MatchingEventEncoder event_encoder_;
  MatchingRuntime runtime_;
  std::size_t maximum_distinct_commands_;
  std::size_t maximum_pending_publications_;
  PartitionSessionState state_{PartitionSessionState::kAwaitingOpen};
  std::string failure_reason_;
  std::optional<std::int64_t> expected_next_input_offset_;
  std::optional<std::int64_t> next_contiguous_offset_;
  std::optional<std::int64_t> highest_contiguous_completed_offset_;
  std::optional<std::int64_t> committed_offset_;
  std::optional<std::int64_t> open_barrier_offset_;
  std::map<CommandIdentity, std::array<char, 65>> command_fingerprints_;
  std::map<std::int64_t, std::unique_ptr<PendingInput>> pending_inputs_;
  std::deque<PendingPublication> pending_publications_;
  std::optional<ActiveCommand> active_command_;
};

/** Minimal adapter loop that proves direct assignment and synchronous contiguous commits. */
class DirectPartitionRuntimeDriver {
public:
  DirectPartitionRuntimeDriver(
      DirectPartitionKafkaConsumer &consumer, PartitionReplayCoordinator &coordinator);

  [[nodiscard]] bool start();
  [[nodiscard]] PartitionReplayResult poll_once();
  [[nodiscard]] bool commit_completed_synchronously();

private:
  DirectPartitionKafkaConsumer &consumer_;
  PartitionReplayCoordinator &coordinator_;
};

} // namespace simplematch::matching
