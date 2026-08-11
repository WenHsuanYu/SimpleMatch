#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <set>
#include <string_view>
#include <utility>

#include <nlohmann/json.hpp>
#include <openssl/sha.h>

namespace simplematch::matching {
namespace {

constexpr std::int32_t kPartitionCount = 15;
constexpr std::string_view kCommandsTopic = "matching.commands";
constexpr char kHex[] = "0123456789abcdef";

bool canonical_sha256(std::string_view value) {
  return value.size() == 64 &&
         std::all_of(value.begin(), value.end(), [](unsigned char character) {
           return (character >= '0' && character <= '9') ||
                  (character >= 'a' && character <= 'f');
         });
}

bool canonical_artifact_identity(std::string_view value) {
  const auto separator = value.find(':');
  return separator != std::string_view::npos && separator > 0 &&
         canonical_sha256(value.substr(separator + 1));
}

bool canonical_image_digest(std::string_view value) {
  return value.starts_with("sha256:") && canonical_sha256(value.substr(7));
}

bool valid_assignment(const DirectKafkaPartitionAssignment &assignment) {
  return assignment.topic == kCommandsTopic && assignment.partition >= 0 &&
         assignment.partition < kPartitionCount;
}

bool valid_identity(const PinnedMatchingIdentity &identity) {
  return canonical_artifact_identity(identity.artifact_identity) &&
         !identity.trading_session_id.empty() &&
         !identity.routing_algorithm_version.empty() &&
         canonical_image_digest(identity.matching_image_digest) &&
         identity.command_schema_version == 1 && identity.event_schema_version == 1 &&
         identity.event_identity_version == 1;
}

std::string uuid_text(const CoreUuid &value) {
  std::string encoded;
  encoded.reserve(36);
  for (std::size_t index = 0; index < value.bytes.size(); ++index) {
    if (index == 4 || index == 6 || index == 8 || index == 10) {
      encoded.push_back('-');
    }
    encoded.push_back(kHex[value.bytes[index] >> 4]);
    encoded.push_back(kHex[value.bytes[index] & 0x0F]);
  }
  return encoded;
}

std::array<char, 65> payload_fingerprint(std::string_view value) {
  std::array<unsigned char, SHA256_DIGEST_LENGTH> digest{};
  SHA256(
      reinterpret_cast<const unsigned char *>(value.data()), value.size(), digest.data());
  std::array<char, 65> encoded{};
  for (std::size_t index = 0; index < digest.size(); ++index) {
    encoded[index * 2] = kHex[digest[index] >> 4];
    encoded[index * 2 + 1] = kHex[digest[index] & 0x0F];
  }
  return encoded;
}

nlohmann::json metadata_json(const PartitionBaselineMetadata &metadata) {
  return {
      {"topic", metadata.assignment.topic},
      {"partition", metadata.assignment.partition},
      {"artifactIdentity", metadata.identity.artifact_identity},
      {"tradingSessionId", metadata.identity.trading_session_id},
      {"routingAlgorithmVersion", metadata.identity.routing_algorithm_version},
      {"matchingImageDigest", metadata.identity.matching_image_digest},
      {"commandSchemaVersion", metadata.identity.command_schema_version},
      {"eventSchemaVersion", metadata.identity.event_schema_version},
      {"eventIdentityVersion", metadata.identity.event_identity_version},
      {"openBarrierOffset", metadata.open_barrier_offset},
      {"committedOffset", metadata.committed_offset}};
}

PartitionBaselineMetadata metadata_from_json(const nlohmann::json &value) {
  const PartitionBaselineMetadata metadata{
      {value.at("topic").get<std::string>(), value.at("partition").get<std::int32_t>()},
      {value.at("artifactIdentity").get<std::string>(),
       value.at("tradingSessionId").get<std::string>(),
       value.at("routingAlgorithmVersion").get<std::string>(),
       value.at("matchingImageDigest").get<std::string>(),
       value.at("commandSchemaVersion").get<std::int32_t>(),
       value.at("eventSchemaVersion").get<std::int32_t>(),
       value.at("eventIdentityVersion").get<std::int32_t>()},
      value.at("openBarrierOffset").get<std::int64_t>(),
      value.at("committedOffset").get<std::int64_t>()};
  if (!valid_assignment(metadata.assignment) || !valid_identity(metadata.identity) ||
      metadata.open_barrier_offset < 0 ||
      metadata.committed_offset < metadata.open_barrier_offset) {
    throw std::runtime_error("invalid partition baseline metadata");
  }
  return metadata;
}

} // namespace

PvcBaselineMetadataStore::PvcBaselineMetadataStore(std::string file_path)
    : file_path_(std::move(file_path)) {
  if (file_path_.empty()) {
    throw std::invalid_argument("baseline metadata path must not be empty");
  }
}

std::optional<PartitionBaselineMetadata> PvcBaselineMetadataStore::load() const {
  if (!std::filesystem::exists(file_path_)) {
    return std::nullopt;
  }
  std::ifstream input(file_path_);
  if (!input) {
    throw std::runtime_error("unable to read partition baseline metadata");
  }
  try {
    nlohmann::json encoded;
    input >> encoded;
    return metadata_from_json(encoded);
  } catch (const nlohmann::json::exception &failure) {
    throw std::runtime_error(
        std::string("invalid partition baseline metadata JSON: ") + failure.what());
  }
}

void PvcBaselineMetadataStore::save(const PartitionBaselineMetadata &metadata) const {
  if (!valid_assignment(metadata.assignment) || !valid_identity(metadata.identity) ||
      metadata.open_barrier_offset < 0 ||
      metadata.committed_offset < metadata.open_barrier_offset) {
    throw std::invalid_argument("invalid partition baseline metadata");
  }
  const std::filesystem::path destination(file_path_);
  if (!destination.parent_path().empty()) {
    std::filesystem::create_directories(destination.parent_path());
  }
  const std::filesystem::path temporary = destination.string() + ".next";
  {
    std::ofstream output(temporary, std::ios::trunc);
    if (!output) {
      throw std::runtime_error("unable to write partition baseline metadata");
    }
    output << metadata_json(metadata).dump() << '\n';
    output.flush();
    if (!output) {
      throw std::runtime_error("unable to flush partition baseline metadata");
    }
  }
  std::filesystem::rename(temporary, destination);
}

PartitionReplayCoordinator::PartitionReplayCoordinator(
    DirectKafkaPartitionAssignment assignment,
    PinnedMatchingIdentity identity,
    std::shared_ptr<const PartitionOwnershipPermit> ownership_permit,
    std::unique_ptr<DeterministicMatchingCore> core,
    std::size_t input_capacity,
    std::size_t output_capacity,
    std::size_t maximum_distinct_commands,
    std::size_t maximum_pending_publications)
    : assignment_(std::move(assignment)),
      identity_(std::move(identity)),
      ownership_permit_(std::move(ownership_permit)),
      runtime_(std::move(core), input_capacity, output_capacity, ownership_permit_),
      maximum_distinct_commands_(maximum_distinct_commands),
      maximum_pending_publications_(maximum_pending_publications) {
  if (!valid_assignment(assignment_) || !valid_identity(identity_) || ownership_permit_ == nullptr ||
      ownership_permit_->partition_id() != assignment_.partition ||
      maximum_distinct_commands_ == 0 ||
      maximum_pending_publications_ < runtime_.maximum_output_events()) {
    throw std::invalid_argument("invalid partition replay coordinator capacity or identity");
  }
}

const DirectKafkaPartitionAssignment &PartitionReplayCoordinator::assignment() const {
  return assignment_;
}

bool PartitionReplayCoordinator::ownership_permitted() const {
  return ownership_permit_->partition_id() == assignment_.partition &&
         ownership_permit_->allows_processing();
}

PartitionReplayResult PartitionReplayCoordinator::ingest(const AssignedCommandRecord &record) {
  return ingest_internal(record, false);
}

PartitionReplayResult PartitionReplayCoordinator::continue_processing() {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!active_command_.has_value()) {
    return PartitionReplayResult::kAccepted;
  }
  return drive_active_command();
}

PartitionReplayResult PartitionReplayCoordinator::ingest_internal(
    const AssignedCommandRecord &record, bool suppress_publication) {
  if (state_ == PartitionSessionState::kFailedClosed) {
    return PartitionReplayResult::kFailedClosed;
  }
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (active_command_.has_value()) {
    return PartitionReplayResult::kInputBackpressured;
  }
  if (record.assignment != assignment_ || record.offset < 0 || record.key.empty() ||
      record.value.empty()) {
    return fail_closed("ASSIGNED_COMMAND_RECORD_INVALID");
  }
  if (expected_next_input_offset_.has_value() &&
      record.offset != *expected_next_input_offset_) {
    return fail_closed("ASSIGNED_COMMAND_OFFSET_GAP");
  }

  const MatchingCommandDecodeResult decoded = decoder_.decode(record.value);
  if (!decoded.accepted()) {
    return fail_closed("MATCHING_COMMAND_DECODE_FAILED");
  }
  if (record.key != uuid_text(decoded.command.command_id)) {
    return fail_closed("MATCHING_COMMAND_KEY_MISMATCH");
  }
  if (!accepts_context(decoded.command.context)) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_COMMAND_IDENTITY_MISMATCH";
    return PartitionReplayResult::kIdentityMismatch;
  }

  const CommandIdentity command_identity{decoded.command.command_id.bytes};
  const auto fingerprint = payload_fingerprint(record.value);
  const auto observed = command_fingerprints_.find(command_identity);
  if (observed != command_fingerprints_.end()) {
    if (observed->second != fingerprint) {
      return fail_closed("MATCHING_COMMAND_IDENTITY_CONFLICT");
    }
    if (!accepts_next_offset(record.offset)) {
      return fail_closed("ASSIGNED_COMMAND_OFFSET_GAP");
    }
    pending_inputs_.emplace(
        record.offset,
        std::make_unique<PendingInput>(PendingInput{decoded.command.context, 0, {}, true}));
    mark_completed(record.offset);
    return PartitionReplayResult::kDuplicate;
  }
  if (command_fingerprints_.size() == maximum_distinct_commands_) {
    return fail_closed("MATCHING_COMMAND_DEDUPLICATION_CAPACITY_EXHAUSTED");
  }
  if (state_ == PartitionSessionState::kAwaitingOpen &&
      decoded.command.type != CoreCommandType::kOpenBarrier) {
    return fail_closed("MATCHING_OPEN_BARRIER_REQUIRED");
  }
  if (state_ == PartitionSessionState::kOpen &&
      decoded.command.type == CoreCommandType::kOpenBarrier) {
    return fail_closed("MATCHING_OPEN_BARRIER_REPEATED");
  }
  if (state_ == PartitionSessionState::kClosed) {
    return fail_closed("MATCHING_PARTITION_CLOSED");
  }
  if (decoded.command.type == CoreCommandType::kOpenBarrier &&
      !accepts_open_barrier(decoded)) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_OPEN_BARRIER_IDENTITY_MISMATCH";
    return PartitionReplayResult::kIdentityMismatch;
  }
  if (pending_publications_.size() + runtime_.maximum_output_events() >
      maximum_pending_publications_) {
    return PartitionReplayResult::kOutputBackpressured;
  }
  if (!runtime_.submit(decoded.command)) {
    return ownership_permitted() ? PartitionReplayResult::kInputBackpressured
                                 : PartitionReplayResult::kOwnershipDenied;
  }

  command_fingerprints_.emplace(command_identity, fingerprint);
  if (!accepts_next_offset(record.offset)) {
    return fail_closed("ASSIGNED_COMMAND_OFFSET_GAP");
  }
  pending_inputs_.emplace(
      record.offset,
      std::make_unique<PendingInput>(PendingInput{decoded.command.context, 0, {}, false}));
  active_command_ = ActiveCommand{
      record.offset, decoded.command.context, decoded.command.type, suppress_publication};
  return drive_active_command();
}

PartitionReplayResult PartitionReplayCoordinator::drive_active_command() {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!active_command_.has_value()) {
    return PartitionReplayResult::kAccepted;
  }
  const MatchingRuntimeStep step = runtime_.process_one();
  if (step == MatchingRuntimeStep::kOutputBackpressured) {
    return PartitionReplayResult::kOutputBackpressured;
  }
  if (step == MatchingRuntimeStep::kOwnershipDenied) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (step == MatchingRuntimeStep::kCoreRejected || step == MatchingRuntimeStep::kNoInput) {
    return fail_closed("MATCHING_CORE_REJECTED_COMMAND");
  }

  const ActiveCommand active = *active_command_;
  const auto pending = pending_inputs_.find(active.input_offset);
  if (pending == pending_inputs_.end()) {
    return fail_closed("MATCHING_PENDING_INPUT_MISSING");
  }
  std::size_t output_count = 0;
  while (const auto event = runtime_.take_output()) {
    ++output_count;
    if (active.suppress_publication) {
      continue;
    }
    if (pending_publications_.size() == maximum_pending_publications_) {
      return fail_closed("MATCHING_PUBLICATION_CAPACITY_EXHAUSTED");
    }
    const auto encoded = event_encoder_.encode(active.context, active.input_offset, *event);
    if (!encoded.has_value()) {
      return fail_closed("MATCHING_EVENT_ENCODING_FAILED");
    }
    pending_publications_.push_back(PendingPublication{*encoded, false});
  }

  pending->second->expected_output_count = active.suppress_publication ? 0 : output_count;
  pending->second->processed = true;
  if (active.type == CoreCommandType::kOpenBarrier) {
    state_ = PartitionSessionState::kOpen;
    open_barrier_offset_ = active.input_offset;
  } else if (active.type == CoreCommandType::kCloseBarrier) {
    state_ = PartitionSessionState::kClosed;
  }
  active_command_.reset();
  if (pending->second->expected_output_count == 0) {
    mark_completed(active.input_offset);
  }
  return PartitionReplayResult::kAccepted;
}

std::optional<MatchingEventRecord>
PartitionReplayCoordinator::next_unacknowledged_publication() const {
  if (!ownership_permitted()) {
    return std::nullopt;
  }
  const auto next = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [](const PendingPublication &publication) { return !publication.acknowledged; });
  if (next == pending_publications_.end()) {
    return std::nullopt;
  }
  return next->record;
}

PartitionReplayResult PartitionReplayCoordinator::acknowledge_published(
    std::int64_t input_offset, std::int32_t output_index) {
  if (state_ == PartitionSessionState::kFailedClosed) {
    return PartitionReplayResult::kFailedClosed;
  }
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  const auto publication = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [input_offset, output_index](const PendingPublication &candidate) {
        return candidate.record.source_input_offset == input_offset &&
               candidate.record.output_index == output_index;
      });
  if (publication == pending_publications_.end()) {
    return PartitionReplayResult::kDuplicate;
  }
  if (publication->acknowledged) {
    return PartitionReplayResult::kDuplicate;
  }
  const auto pending = pending_inputs_.find(input_offset);
  if (pending == pending_inputs_.end() || !pending->second->processed) {
    return fail_closed("MATCHING_PUBLICATION_INPUT_MISSING");
  }
  publication->acknowledged = true;
  if (!pending->second->acknowledged_output_indices.insert(output_index).second) {
    return PartitionReplayResult::kDuplicate;
  }
  const bool all_outputs_acknowledged =
      pending->second->acknowledged_output_indices.size() == pending->second->expected_output_count;
  while (!pending_publications_.empty() && pending_publications_.front().acknowledged) {
    pending_publications_.pop_front();
  }
  if (all_outputs_acknowledged) {
    mark_completed(input_offset);
  }
  return continue_processing();
}

std::optional<std::int64_t> PartitionReplayCoordinator::next_commit_offset() const {
  if (!ownership_permitted()) {
    return std::nullopt;
  }
  if (!highest_contiguous_completed_offset_.has_value() ||
      (committed_offset_.has_value() &&
       *highest_contiguous_completed_offset_ <= *committed_offset_)) {
    return std::nullopt;
  }
  return *highest_contiguous_completed_offset_ + 1;
}

bool PartitionReplayCoordinator::acknowledge_commit(std::int64_t next_offset) {
  const auto expected = next_commit_offset();
  if (!expected.has_value() || *expected != next_offset) {
    return false;
  }
  committed_offset_ = next_offset - 1;
  return true;
}

std::optional<PartitionBaselineMetadata> PartitionReplayCoordinator::baseline_metadata() const {
  if (!open_barrier_offset_.has_value() || !committed_offset_.has_value()) {
    return std::nullopt;
  }
  return PartitionBaselineMetadata{
      assignment_, identity_, *open_barrier_offset_, *committed_offset_};
}

PartitionReplayStatus PartitionReplayCoordinator::status() const {
  return {state_,
          ownership_permit_->status(),
          highest_contiguous_completed_offset_,
          next_commit_offset(),
          pending_inputs_.size(),
          pending_publications_.size(),
          failure_reason_};
}

PartitionReplayResult PartitionReplayCoordinator::recover(
    const PartitionBaselineMetadata &baseline,
    std::int64_t earliest_retained_offset,
    std::span<const AssignedCommandRecord> retained_records) {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (state_ != PartitionSessionState::kAwaitingOpen || active_command_.has_value() ||
      baseline.assignment != assignment_ || baseline.identity != identity_) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_BASELINE_IDENTITY_MISMATCH";
    return PartitionReplayResult::kIdentityMismatch;
  }
  if (earliest_retained_offset < 0 || baseline.open_barrier_offset < earliest_retained_offset) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_OPEN_BARRIER_NOT_RETAINED";
    return PartitionReplayResult::kMissingRetainedOpenBarrier;
  }
  expected_next_input_offset_ = baseline.open_barrier_offset;
  next_contiguous_offset_ = baseline.open_barrier_offset;
  highest_contiguous_completed_offset_.reset();
  committed_offset_.reset();

  for (std::int64_t offset = baseline.open_barrier_offset;
       offset <= baseline.committed_offset;
       ++offset) {
    const auto record = std::find_if(
        retained_records.begin(), retained_records.end(), [offset](const AssignedCommandRecord &candidate) {
          return candidate.offset == offset;
        });
    if (record == retained_records.end()) {
      state_ = PartitionSessionState::kFailedClosed;
      failure_reason_ = "MATCHING_REPLAY_RETENTION_GAP";
      return PartitionReplayResult::kRetentionInsufficient;
    }
    const auto result = ingest_internal(*record, true);
    if (result != PartitionReplayResult::kAccepted && result != PartitionReplayResult::kDuplicate) {
      return result;
    }
  }
  if (!highest_contiguous_completed_offset_.has_value() ||
      *highest_contiguous_completed_offset_ != baseline.committed_offset ||
      !open_barrier_offset_.has_value()) {
    return fail_closed("MATCHING_REPLAY_INCOMPLETE");
  }
  committed_offset_ = baseline.committed_offset;
  return PartitionReplayResult::kRecovered;
}

PartitionReplayResult PartitionReplayCoordinator::recover_from_retained_records(
    std::int64_t earliest_retained_offset,
    std::int64_t committed_offset,
    std::span<const AssignedCommandRecord> retained_records) {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (earliest_retained_offset < 0 || committed_offset < earliest_retained_offset) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_RETENTION_RANGE_INVALID";
    return PartitionReplayResult::kRetentionInsufficient;
  }
  std::optional<std::int64_t> open_offset;
  for (const AssignedCommandRecord &record : retained_records) {
    if (record.offset < earliest_retained_offset || record.offset > committed_offset ||
        record.assignment != assignment_) {
      continue;
    }
    const MatchingCommandDecodeResult decoded = decoder_.decode(record.value);
    if (decoded.accepted() && decoded.command.type == CoreCommandType::kOpenBarrier &&
        record.key == uuid_text(decoded.command.command_id) &&
        accepts_context(decoded.command.context) && accepts_open_barrier(decoded)) {
      open_offset = record.offset;
    }
  }
  if (!open_offset.has_value()) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_OPEN_BARRIER_NOT_RETAINED";
    return PartitionReplayResult::kMissingRetainedOpenBarrier;
  }
  return recover(
      PartitionBaselineMetadata{assignment_, identity_, *open_offset, committed_offset},
      earliest_retained_offset,
      retained_records);
}

bool PartitionReplayCoordinator::accepts_context(const MatchingCommandContext &context) const {
  return context.partition_id == assignment_.partition &&
         context.artifact_identity.view() == identity_.artifact_identity &&
         context.trading_session_id.view() == identity_.trading_session_id &&
         context.routing_algorithm_version.view() == identity_.routing_algorithm_version;
}

bool PartitionReplayCoordinator::accepts_open_barrier(
    const MatchingCommandDecodeResult &decoded) const {
  return decoded.metadata.command_schema_version == identity_.command_schema_version &&
         decoded.metadata.event_schema_version == identity_.event_schema_version &&
         decoded.metadata.event_identity_version == identity_.event_identity_version &&
         decoded.metadata.matching_image_digest == identity_.matching_image_digest;
}

bool PartitionReplayCoordinator::accepts_next_offset(std::int64_t offset) {
  if (offset < 0 ||
      (expected_next_input_offset_.has_value() && offset != *expected_next_input_offset_)) {
    return false;
  }
  if (!expected_next_input_offset_.has_value()) {
    expected_next_input_offset_ = offset;
    next_contiguous_offset_ = offset;
  }
  expected_next_input_offset_ = offset + 1;
  return true;
}

void PartitionReplayCoordinator::mark_completed(std::int64_t input_offset) {
  const auto pending = pending_inputs_.find(input_offset);
  if (pending == pending_inputs_.end()) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_COMPLETION_INPUT_MISSING";
    return;
  }
  pending->second->processed = true;
  while (next_contiguous_offset_.has_value()) {
    const auto next = pending_inputs_.find(*next_contiguous_offset_);
    if (next == pending_inputs_.end() || !next->second->processed ||
        next->second->acknowledged_output_indices.size() != next->second->expected_output_count) {
      break;
    }
    highest_contiguous_completed_offset_ = *next_contiguous_offset_;
    ++*next_contiguous_offset_;
    pending_inputs_.erase(next);
  }
}

PartitionReplayResult PartitionReplayCoordinator::fail_closed(std::string reason) {
  state_ = PartitionSessionState::kFailedClosed;
  failure_reason_ = std::move(reason);
  return PartitionReplayResult::kFailedClosed;
}

DirectPartitionRuntimeDriver::DirectPartitionRuntimeDriver(
    DirectPartitionKafkaConsumer &consumer, PartitionReplayCoordinator &coordinator)
    : consumer_(consumer), coordinator_(coordinator) {}

bool DirectPartitionRuntimeDriver::start() {
  if (!coordinator_.ownership_permitted()) {
    return false;
  }
  consumer_.assign(coordinator_.assignment());
  return true;
}

PartitionReplayResult DirectPartitionRuntimeDriver::poll_once() {
  if (!coordinator_.ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  const auto record = consumer_.poll();
  return record.has_value() ? coordinator_.ingest(*record) : coordinator_.continue_processing();
}

bool DirectPartitionRuntimeDriver::commit_completed_synchronously() {
  if (!coordinator_.ownership_permitted()) {
    return false;
  }
  const auto next_offset = coordinator_.next_commit_offset();
  if (!next_offset.has_value()) {
    return false;
  }
  consumer_.commit_synchronously(*next_offset);
  return coordinator_.acknowledge_commit(*next_offset);
}

} // namespace simplematch::matching
