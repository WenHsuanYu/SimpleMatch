#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <limits>
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

RetainedRecordBatchReader span_batch_reader(
    std::span<const AssignedCommandRecord> retained_records) {
  return [retained_records](
             std::int64_t first_offset,
             std::int64_t end_offset,
             std::size_t maximum_records) {
    std::vector<AssignedCommandRecord> batch;
    if (maximum_records == 0) {
      return batch;
    }
    batch.reserve(maximum_records);
    for (const auto &record : retained_records) {
      if (record.offset >= first_offset && record.offset < end_offset) {
        batch.push_back(record);
        if (batch.size() == maximum_records) {
          break;
        }
      }
    }
    return batch;
  };
}

std::int64_t bounded_batch_end(
    std::int64_t first_offset, std::int64_t end_offset, std::size_t maximum_records) {
  const auto available = static_cast<std::uintmax_t>(end_offset - first_offset);
  const auto width = std::min(available, static_cast<std::uintmax_t>(maximum_records));
  return first_offset + static_cast<std::int64_t>(width);
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
      input_ledger_(input_capacity),
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

MatchingRuntime &PartitionReplayCoordinator::runtime() {
  return runtime_;
}

const MatchingRuntime &PartitionReplayCoordinator::runtime() const {
  return runtime_;
}

std::shared_ptr<const PartitionOwnershipPermit>
PartitionReplayCoordinator::ownership_permit() const {
  return ownership_permit_;
}

bool PartitionReplayCoordinator::ownership_permitted() const {
  return ownership_permit_->partition_id() == assignment_.partition &&
         ownership_permit_->allows_processing();
}

PartitionReplayResult PartitionReplayCoordinator::ingest(const AssignedCommandRecord &record) {
  return ingest_internal(record, false, true);
}

PartitionReplayResult PartitionReplayCoordinator::ingest_for_threaded_writer(
    const AssignedCommandRecord &record, bool suppress_publication) {
  return ingest_internal(record, suppress_publication, false);
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

PartitionReplayResult PartitionReplayCoordinator::drain_threaded_outputs() {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  return drain_active_command_outputs();
}

PartitionReplayResult PartitionReplayCoordinator::ingest_internal(
    const AssignedCommandRecord &record, bool suppress_publication, bool process_immediately) {
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
    if (input_ledger_.pending_count() == input_ledger_.capacity()) {
      return PartitionReplayResult::kInputBackpressured;
    }
    const auto input_sequence = runtime_.reserve_input_sequence();
    if (!input_sequence.has_value()) {
      return ownership_permitted() ? PartitionReplayResult::kInputBackpressured
                                   : PartitionReplayResult::kOwnershipDenied;
    }
    if (input_ledger_.append(*input_sequence, record.offset) != InputLedgerResult::kAccepted) {
      return fail_closed("MATCHING_INPUT_LEDGER_APPEND_FAILED");
    }
    if (!accepts_next_offset(record.offset)) {
      return fail_closed("ASSIGNED_COMMAND_OFFSET_GAP");
    }
    pending_inputs_.emplace(
        record.offset,
        std::make_unique<PendingInput>(
            PendingInput{*input_sequence, decoded.command.context, 0, {}, true}));
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
  if (input_ledger_.pending_count() == input_ledger_.capacity()) {
    return PartitionReplayResult::kInputBackpressured;
  }
  const auto input_sequence = runtime_.submit(decoded.command);
  if (!input_sequence.has_value()) {
    return ownership_permitted() ? PartitionReplayResult::kInputBackpressured
                                 : PartitionReplayResult::kOwnershipDenied;
  }
  if (input_ledger_.append(*input_sequence, record.offset) != InputLedgerResult::kAccepted) {
    return fail_closed("MATCHING_INPUT_LEDGER_APPEND_FAILED");
  }

  command_fingerprints_.emplace(command_identity, fingerprint);
  if (!accepts_next_offset(record.offset)) {
    return fail_closed("ASSIGNED_COMMAND_OFFSET_GAP");
  }
  pending_inputs_.emplace(
      record.offset,
      std::make_unique<PendingInput>(
          PendingInput{*input_sequence, decoded.command.context, 0, {}, false}));
  active_command_ = ActiveCommand{
      *input_sequence,
      record.offset,
      decoded.command.context,
      decoded.command.type,
      suppress_publication,
      0};
  return process_immediately ? drive_active_command() : PartitionReplayResult::kAccepted;
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

  return drain_active_command_outputs();
}

PartitionReplayResult PartitionReplayCoordinator::drain_active_command_outputs() {
  if (!active_command_.has_value()) {
    return PartitionReplayResult::kAccepted;
  }

  ActiveCommand &active = *active_command_;
  const auto pending = pending_inputs_.find(active.input_offset);
  if (pending == pending_inputs_.end()) {
    return fail_closed("MATCHING_PENDING_INPUT_MISSING");
  }
  bool saw_end_of_input = false;
  while (const auto output = runtime_.take_output()) {
    if (const auto *end = std::get_if<RuntimeEndOfInput>(&*output)) {
      if (end->input_sequence != active.input_sequence || end->output_count != active.output_count) {
        return fail_closed("MATCHING_OUTPUT_SEQUENCE_CORRUPTED");
      }
      saw_end_of_input = true;
      break;
    }
    const auto &event = std::get<RuntimeEventOutput>(*output);
    if (event.input_sequence != active.input_sequence ||
        event.output_index != active.output_count || event.event.output_index < 0 ||
        static_cast<std::size_t>(event.event.output_index) != active.output_count) {
      return fail_closed("MATCHING_OUTPUT_SEQUENCE_CORRUPTED");
    }
    ++active.output_count;
    if (active.suppress_publication) {
      continue;
    }
    if (pending_publications_.size() == maximum_pending_publications_) {
      return fail_closed("MATCHING_PUBLICATION_CAPACITY_EXHAUSTED");
    }
    const auto encoded = event_encoder_.encode(active.context, active.input_offset, event.event);
    if (!encoded.has_value()) {
      return fail_closed("MATCHING_EVENT_ENCODING_FAILED");
    }
    if (next_publication_id_ == std::numeric_limits<std::uint64_t>::max()) {
      return fail_closed("MATCHING_PUBLICATION_ID_EXHAUSTED");
    }
    pending_publications_.push_back(
        PendingPublication{*encoded, false, false, next_publication_id_++});
  }
  if (!saw_end_of_input) {
    return PartitionReplayResult::kInputBackpressured;
  }

  pending->second->expected_output_count = active.suppress_publication ? 0 : active.output_count;
  pending->second->processed = true;
  if (active.type == CoreCommandType::kOpenBarrier) {
    state_ = PartitionSessionState::kOpen;
    open_barrier_offset_ = active.input_offset;
  } else if (active.type == CoreCommandType::kCloseBarrier) {
    state_ = PartitionSessionState::kClosed;
  }
  const auto completed_input_offset = active.input_offset;
  active_command_.reset();
  if (pending->second->expected_output_count == 0) {
    mark_completed(completed_input_offset);
  }
  return PartitionReplayResult::kAccepted;
}

std::optional<MatchingEventRecord>
PartitionReplayCoordinator::next_unacknowledged_publication() const {
  if (!ownership_permitted()) {
    return std::nullopt;
  }
  if (retry_publication_id_.has_value()) {
    const auto retry = std::find_if(
        pending_publications_.begin(), pending_publications_.end(),
        [this](const PendingPublication &publication) {
          const auto pending = pending_inputs_.find(publication.record.source_input_offset);
          return publication.publication_id == *retry_publication_id_ &&
                 pending != pending_inputs_.end() && pending->second->processed;
        });
    if (retry == pending_publications_.end() || retry->acknowledged || retry->submitted) {
      return std::nullopt;
    }
    return retry->record;
  }
  const auto next = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [this](const PendingPublication &publication) {
        const auto pending = pending_inputs_.find(publication.record.source_input_offset);
        return !publication.acknowledged && !publication.submitted &&
               pending != pending_inputs_.end() && pending->second->processed;
      });
  if (next == pending_publications_.end()) {
    return std::nullopt;
  }
  return next->record;
}

bool PartitionReplayCoordinator::publication_retry_pending() const noexcept {
  return retry_publication_id_.has_value();
}

std::optional<std::uint64_t> PartitionReplayCoordinator::publication_id_for(
    std::int64_t input_offset, std::int32_t output_index) const {
  const auto publication = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [input_offset, output_index](const PendingPublication &candidate) {
        return candidate.record.source_input_offset == input_offset &&
               candidate.record.output_index == output_index;
      });
  if (publication == pending_publications_.end() || publication->acknowledged) {
    return std::nullopt;
  }
  return publication->publication_id;
}

PartitionReplayResult PartitionReplayCoordinator::mark_publication_submitted(
    std::int64_t input_offset, std::int32_t output_index, std::uint64_t publication_id) {
  const auto publication = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [input_offset, output_index](const PendingPublication &candidate) {
        return candidate.record.source_input_offset == input_offset &&
               candidate.record.output_index == output_index;
      });
  if (publication == pending_publications_.end() || publication->acknowledged ||
      publication->publication_id != publication_id) {
    return fail_closed("MATCHING_PUBLICATION_SUBMISSION_UNKNOWN");
  }
  if (publication->submitted) {
    return PartitionReplayResult::kDuplicate;
  }
  publication->submitted = true;
  return PartitionReplayResult::kAccepted;
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
  publication->submitted = true;
  return acknowledge_publication(*publication);
}

PartitionReplayResult PartitionReplayCoordinator::acknowledge_published(
    std::uint64_t publication_id, MatchingPublicationDelivery delivery) {
  const auto publication = std::find_if(
      pending_publications_.begin(), pending_publications_.end(),
      [publication_id](const PendingPublication &candidate) {
        return candidate.publication_id == publication_id;
      });
  if (publication == pending_publications_.end() || publication->acknowledged) {
    return PartitionReplayResult::kDuplicate;
  }
  if (!publication->submitted) {
    return fail_closed("MATCHING_PUBLICATION_ACK_BEFORE_SUBMISSION");
  }
  if (delivery == MatchingPublicationDelivery::kNotPersisted ||
      delivery == MatchingPublicationDelivery::kPossiblyPersisted) {
    if (retry_publication_id_.has_value() && *retry_publication_id_ != publication_id) {
      return fail_closed("MATCHING_MULTIPLE_PUBLICATION_RETRIES_REQUIRED");
    }
    retry_publication_id_ = publication_id;
    publication->submitted = false;
    return PartitionReplayResult::kOutputBackpressured;
  }
  if (delivery == MatchingPublicationDelivery::kFatal) {
    return fail_closed("MATCHING_PUBLICATION_FATAL_FAILURE");
  }
  return acknowledge_publication(*publication);
}

PartitionReplayResult PartitionReplayCoordinator::acknowledge_publication(
    PendingPublication &publication) {
  const auto input_offset = publication.record.source_input_offset;
  const auto publication_id = publication.publication_id;
  const auto pending = pending_inputs_.find(input_offset);
  if (pending == pending_inputs_.end() || !pending->second->processed) {
    return fail_closed("MATCHING_PUBLICATION_INPUT_MISSING");
  }
  publication.acknowledged = true;
  publication.submitted = false;
  if (!pending->second->acknowledged_output_indices.insert(
          publication.record.output_index).second) {
    return PartitionReplayResult::kDuplicate;
  }
  const bool all_outputs_acknowledged =
      pending->second->acknowledged_output_indices.size() == pending->second->expected_output_count;
  const auto completed_input_offset = publication.record.source_input_offset;
  while (!pending_publications_.empty() && pending_publications_.front().acknowledged) {
    pending_publications_.pop_front();
  }
  if (all_outputs_acknowledged) {
    mark_completed(completed_input_offset);
  }
  if (retry_publication_id_ == publication_id) {
    retry_publication_id_.reset();
  }
  return PartitionReplayResult::kAccepted;
}

std::optional<std::int64_t> PartitionReplayCoordinator::next_commit_offset() const {
  if (!ownership_permitted()) {
    return std::nullopt;
  }
  return input_ledger_.next_commit_offset();
}

bool PartitionReplayCoordinator::acknowledge_commit(std::int64_t next_offset) {
  if (!input_ledger_.acknowledge_commit(next_offset)) {
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
          input_ledger_.highest_contiguous_completed_offset(),
          next_commit_offset(),
          pending_inputs_.size(),
          pending_publications_.size(),
          failure_reason_};
}

void PartitionReplayCoordinator::record_runtime_failure(std::string reason) {
  if (state_ == PartitionSessionState::kFailedClosed) {
    return;
  }
  state_ = PartitionSessionState::kFailedClosed;
  failure_reason_ = std::move(reason);
}

PartitionReplayResult PartitionReplayCoordinator::recover(
    const PartitionBaselineMetadata &baseline,
    std::int64_t earliest_retained_offset,
    std::span<const AssignedCommandRecord> retained_records) {
  return recover(baseline, earliest_retained_offset, span_batch_reader(retained_records));
}

PartitionReplayResult PartitionReplayCoordinator::recover(
    const PartitionBaselineMetadata &baseline,
    std::int64_t earliest_retained_offset,
    const RetainedRecordBatchReader &reader) {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!reader) {
    return fail_closed("MATCHING_REPLAY_READER_MISSING");
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
  if (baseline.committed_offset < baseline.open_barrier_offset ||
      baseline.committed_offset == std::numeric_limits<std::int64_t>::max()) {
    return fail_retention("MATCHING_REPLAY_RANGE_INVALID");
  }
  return recover_from_batches(baseline, reader, true, nullptr);
}

PartitionReplayResult PartitionReplayCoordinator::recover_threaded(
    const PartitionBaselineMetadata &baseline,
    std::int64_t earliest_retained_offset,
    const RetainedRecordBatchReader &reader,
    const ThreadedRecoveryStep &execution_step) {
  if (!execution_step) {
    return fail_closed("MATCHING_REPLAY_EXECUTION_STEP_MISSING");
  }
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!reader) {
    return fail_closed("MATCHING_REPLAY_READER_MISSING");
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
  if (baseline.committed_offset < baseline.open_barrier_offset ||
      baseline.committed_offset == std::numeric_limits<std::int64_t>::max()) {
    return fail_retention("MATCHING_REPLAY_RANGE_INVALID");
  }
  return recover_from_batches(baseline, reader, false, &execution_step);
}

PartitionReplayResult PartitionReplayCoordinator::recover_from_batches(
    const PartitionBaselineMetadata &baseline,
    const RetainedRecordBatchReader &reader,
    bool process_immediately,
    const ThreadedRecoveryStep *execution_step) {
  expected_next_input_offset_ = baseline.open_barrier_offset;
  committed_offset_.reset();

  std::int64_t next_offset = baseline.open_barrier_offset;
  const auto end_offset = baseline.committed_offset + 1;
  while (next_offset < end_offset) {
    const auto batch_end = bounded_batch_end(next_offset, end_offset, input_ledger_.capacity());
    const auto batch = reader(next_offset, batch_end, input_ledger_.capacity());
    if (batch.empty()) {
      return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
    }
    if (batch.size() > input_ledger_.capacity() ||
        batch.size() > static_cast<std::size_t>(batch_end - next_offset)) {
      return fail_closed("MATCHING_REPLAY_BATCH_CAPACITY_EXCEEDED");
    }
    for (const auto &record : batch) {
      if (record.offset != next_offset) {
        return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
      }
      const auto result = ingest_internal(record, true, process_immediately);
      if (result != PartitionReplayResult::kAccepted &&
          result != PartitionReplayResult::kDuplicate) {
        return result;
      }
      if (result == PartitionReplayResult::kAccepted && !process_immediately) {
        if (execution_step == nullptr) {
          return fail_closed("MATCHING_REPLAY_EXECUTION_STEP_MISSING");
        }
        const auto execution_result = (*execution_step)();
        if (execution_result != PartitionReplayResult::kAccepted &&
            execution_result != PartitionReplayResult::kDuplicate) {
          return execution_result;
        }
      }
      ++next_offset;
    }
  }
  if (!input_ledger_.highest_contiguous_completed_offset().has_value() ||
      *input_ledger_.highest_contiguous_completed_offset() != baseline.committed_offset ||
      !open_barrier_offset_.has_value()) {
    return fail_closed("MATCHING_REPLAY_INCOMPLETE");
  }
  committed_offset_ = baseline.committed_offset;
  if (!input_ledger_.acknowledge_commit(baseline.committed_offset + 1)) {
    return fail_closed("MATCHING_REPLAY_COMMIT_WATERMARK_INVALID");
  }
  return PartitionReplayResult::kRecovered;
}

PartitionReplayResult PartitionReplayCoordinator::recover_from_retained_records(
    std::int64_t earliest_retained_offset,
    std::int64_t committed_offset,
    std::span<const AssignedCommandRecord> retained_records) {
  return recover_from_retained_batches(
      earliest_retained_offset, committed_offset, span_batch_reader(retained_records));
}

PartitionReplayResult PartitionReplayCoordinator::recover_from_retained_batches(
    std::int64_t earliest_retained_offset,
    std::int64_t committed_offset,
    const RetainedRecordBatchReader &reader) {
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!reader) {
    return fail_closed("MATCHING_REPLAY_READER_MISSING");
  }
  if (earliest_retained_offset < 0 || committed_offset < earliest_retained_offset ||
      committed_offset == std::numeric_limits<std::int64_t>::max()) {
    return fail_retention("MATCHING_RETENTION_RANGE_INVALID");
  }
  std::optional<std::int64_t> open_offset;
  std::int64_t next_offset = earliest_retained_offset;
  const auto end_offset = committed_offset + 1;
  while (next_offset < end_offset) {
    const auto batch_end = bounded_batch_end(next_offset, end_offset, input_ledger_.capacity());
    const auto batch = reader(next_offset, batch_end, input_ledger_.capacity());
    if (batch.empty()) {
      return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
    }
    if (batch.size() > input_ledger_.capacity() ||
        batch.size() > static_cast<std::size_t>(batch_end - next_offset)) {
      return fail_closed("MATCHING_REPLAY_BATCH_CAPACITY_EXCEEDED");
    }
    for (const auto &record : batch) {
      if (record.offset != next_offset) {
        return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
      }
      if (record.assignment == assignment_) {
        const MatchingCommandDecodeResult decoded = decoder_.decode(record.value);
        if (decoded.accepted() && decoded.command.type == CoreCommandType::kOpenBarrier &&
            record.key == uuid_text(decoded.command.command_id) &&
            accepts_context(decoded.command.context) && accepts_open_barrier(decoded)) {
          open_offset = record.offset;
        }
      }
      ++next_offset;
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
      reader);
}

PartitionReplayResult PartitionReplayCoordinator::recover_from_retained_batches_threaded(
    std::int64_t earliest_retained_offset,
    std::int64_t committed_offset,
    const RetainedRecordBatchReader &reader,
    const ThreadedRecoveryStep &execution_step) {
  if (!execution_step) {
    return fail_closed("MATCHING_REPLAY_EXECUTION_STEP_MISSING");
  }
  if (!ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  if (!reader) {
    return fail_closed("MATCHING_REPLAY_READER_MISSING");
  }
  if (earliest_retained_offset < 0 || committed_offset < earliest_retained_offset ||
      committed_offset == std::numeric_limits<std::int64_t>::max()) {
    return fail_retention("MATCHING_RETENTION_RANGE_INVALID");
  }
  std::optional<std::int64_t> open_offset;
  std::int64_t next_offset = earliest_retained_offset;
  const auto end_offset = committed_offset + 1;
  while (next_offset < end_offset) {
    const auto batch_end = bounded_batch_end(next_offset, end_offset, input_ledger_.capacity());
    const auto batch = reader(next_offset, batch_end, input_ledger_.capacity());
    if (batch.empty()) {
      return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
    }
    if (batch.size() > input_ledger_.capacity() ||
        batch.size() > static_cast<std::size_t>(batch_end - next_offset)) {
      return fail_closed("MATCHING_REPLAY_BATCH_CAPACITY_EXCEEDED");
    }
    for (const auto &record : batch) {
      if (record.offset != next_offset) {
        return fail_retention("MATCHING_REPLAY_RETENTION_GAP");
      }
      if (record.assignment == assignment_) {
        const MatchingCommandDecodeResult decoded = decoder_.decode(record.value);
        if (decoded.accepted() && decoded.command.type == CoreCommandType::kOpenBarrier &&
            record.key == uuid_text(decoded.command.command_id) &&
            accepts_context(decoded.command.context) && accepts_open_barrier(decoded)) {
          open_offset = record.offset;
        }
      }
      ++next_offset;
    }
  }
  if (!open_offset.has_value()) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_OPEN_BARRIER_NOT_RETAINED";
    return PartitionReplayResult::kMissingRetainedOpenBarrier;
  }
  return recover_threaded(
      PartitionBaselineMetadata{assignment_, identity_, *open_offset, committed_offset},
      earliest_retained_offset,
      reader,
      execution_step);
}

bool PartitionReplayCoordinator::validate_recovery_baseline(
    const PartitionBaselineMetadata &baseline,
    std::int64_t earliest_retained_offset,
    std::int64_t committed_next_offset) const {
  return baseline.assignment == assignment_ && baseline.identity == identity_ &&
         earliest_retained_offset >= 0 && baseline.open_barrier_offset >= earliest_retained_offset &&
         baseline.committed_offset >= baseline.open_barrier_offset &&
         baseline.committed_offset < committed_next_offset;
}

std::optional<PartitionBaselineMetadata>
PartitionReplayCoordinator::discover_retained_recovery_baseline(
    std::int64_t earliest_retained_offset,
    std::int64_t committed_offset,
    const RetainedRecordBatchReader &reader) const {
  if (!reader || earliest_retained_offset < 0 || committed_offset < earliest_retained_offset ||
      committed_offset == std::numeric_limits<std::int64_t>::max()) {
    return std::nullopt;
  }
  std::optional<std::int64_t> open_offset;
  std::int64_t next_offset = earliest_retained_offset;
  const auto end_offset = committed_offset + 1;
  while (next_offset < end_offset) {
    const auto batch_end = bounded_batch_end(next_offset, end_offset, input_ledger_.capacity());
    const auto batch = reader(next_offset, batch_end, input_ledger_.capacity());
    if (batch.empty() || batch.size() > input_ledger_.capacity() ||
        batch.size() > static_cast<std::size_t>(batch_end - next_offset)) {
      return std::nullopt;
    }
    for (const auto &record : batch) {
      if (record.offset != next_offset) {
        return std::nullopt;
      }
      if (record.assignment == assignment_) {
        const MatchingCommandDecodeResult decoded = decoder_.decode(record.value);
        if (decoded.accepted() && decoded.command.type == CoreCommandType::kOpenBarrier &&
            record.key == uuid_text(decoded.command.command_id) &&
            accepts_context(decoded.command.context) && accepts_open_barrier(decoded)) {
          open_offset = record.offset;
        }
      }
      ++next_offset;
    }
  }
  if (!open_offset.has_value()) {
    return std::nullopt;
  }
  return PartitionBaselineMetadata{assignment_, identity_, *open_offset, committed_offset};
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
  const auto result = input_ledger_.complete(pending->second->input_sequence);
  if (result != InputLedgerResult::kAccepted && result != InputLedgerResult::kDuplicate) {
    state_ = PartitionSessionState::kFailedClosed;
    failure_reason_ = "MATCHING_INPUT_LEDGER_COMPLETION_FAILED";
    return;
  }
  release_completed_inputs();
}

void PartitionReplayCoordinator::release_completed_inputs() {
  const auto highest = input_ledger_.highest_contiguous_completed_sequence();
  if (!highest.has_value()) {
    return;
  }
  for (auto pending = pending_inputs_.begin(); pending != pending_inputs_.end();) {
    if (pending->second->input_sequence <= *highest) {
      pending = pending_inputs_.erase(pending);
    } else {
      ++pending;
    }
  }
}

PartitionReplayResult PartitionReplayCoordinator::fail_closed(std::string reason) {
  state_ = PartitionSessionState::kFailedClosed;
  failure_reason_ = std::move(reason);
  return PartitionReplayResult::kFailedClosed;
}

PartitionReplayResult PartitionReplayCoordinator::fail_retention(std::string reason) {
  state_ = PartitionSessionState::kFailedClosed;
  failure_reason_ = std::move(reason);
  return PartitionReplayResult::kRetentionInsufficient;
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

PartitionReplayResult DirectPartitionRuntimeDriver::poll_once_threaded() {
  if (!coordinator_.ownership_permitted()) {
    return PartitionReplayResult::kOwnershipDenied;
  }
  const auto record = consumer_.poll();
  return record.has_value() ? coordinator_.ingest_for_threaded_writer(*record)
                            : PartitionReplayResult::kAccepted;
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
