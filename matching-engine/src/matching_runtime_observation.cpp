#include "simplematch/matching/runtime/matching_runtime_observation.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <utility>

namespace simplematch::matching {
namespace {

using Json = nlohmann::json;

constexpr std::int32_t kPartitionCount = 15;

bool canonical_sha256(std::string_view value) {
  return value.size() == 64 &&
         std::all_of(value.begin(), value.end(), [](unsigned char character) {
           return (character >= '0' && character <= '9') ||
                  (character >= 'a' && character <= 'f');
         });
}

Json optional_offset(std::optional<std::int64_t> offset) {
  return offset.has_value() ? Json(*offset) : Json(nullptr);
}

std::string partition_state_name(PartitionSessionState state) {
  switch (state) {
    case PartitionSessionState::kAwaitingOpen:
      return "AWAITING_OPEN";
    case PartitionSessionState::kOpen:
      return "OPEN";
    case PartitionSessionState::kClosed:
      return "CLOSED";
    case PartitionSessionState::kFailedClosed:
      return "FAILED_CLOSED";
  }
  throw std::invalid_argument("unsupported Matching partition state");
}

std::pair<std::string, std::string> artifact_parts(std::string_view artifact_identity) {
  const auto separator = artifact_identity.find(':');
  if (separator == std::string_view::npos || separator == 0 ||
      separator + 1 == artifact_identity.size()) {
    throw std::invalid_argument("Matching artifact identity is incomplete");
  }
  auto trading_day = std::string(artifact_identity.substr(0, separator));
  auto checksum = std::string(artifact_identity.substr(separator + 1));
  if (!canonical_sha256(checksum)) {
    throw std::invalid_argument("Matching artifact checksum is not canonical SHA-256");
  }
  return {std::move(trading_day), std::move(checksum)};
}

bool ownership_permitted(const MatchingRuntimeObservation &observation) {
  return observation.replay.ownership.state == PartitionOwnershipState::kPermitted;
}

bool recovery_complete(const MatchingRuntimeObservation &observation) {
  return observation.replay.state == PartitionSessionState::kOpen;
}

bool supported_runtime_state(std::string_view state) {
  return state == "NOT_READY" || state == "RUNNING" || state == "READY" ||
         state == "STOPPED" || state == "FAILED";
}

void validate_observation(
    const MatchingRuntimeObservation &observation,
    std::string_view trading_day) {
  const auto &identity = observation.identity;
  if (observation.partition_id < 0 || observation.partition_id >= kPartitionCount ||
      observation.owner_id.empty() || observation.updated_at_epoch_ms <= 0 ||
      observation.artifact_id != "market-reference-" + std::string(trading_day) ||
      identity.trading_session_id.empty() || identity.routing_algorithm_version.empty() ||
      !identity.matching_image_digest.starts_with("sha256:") ||
      !canonical_sha256(std::string_view(identity.matching_image_digest).substr(7)) ||
      identity.command_schema_version != 1 || identity.event_schema_version != 1 ||
      identity.event_identity_version != 1 ||
      !supported_runtime_state(observation.runtime_state)) {
    throw std::invalid_argument("Matching runtime observation is invalid");
  }
}

Json admission_json(
    const MatchingRuntimeObservation &observation,
    std::string_view artifact_checksum) {
  return {
      {"partition_id", observation.partition_id},
      {"owner_id", observation.owner_id},
      {"identity",
       {{"trading_session_id", observation.identity.trading_session_id},
        {"artifact",
         {{"id", observation.artifact_id},
          {"content_sha256", artifact_checksum}}},
        {"command_schema_version", observation.identity.command_schema_version},
        {"event_schema_version", observation.identity.event_schema_version},
        {"matching_algorithm_version", observation.identity.routing_algorithm_version},
        {"matching_image_identity", observation.identity.matching_image_digest}}},
      {"ownership_permitted", ownership_permitted(observation)},
      {"recovery_complete", recovery_complete(observation)}};
}

} // namespace

std::string encode_matching_runtime_observation(
    const MatchingRuntimeObservation &observation) {
  const auto [trading_day, artifact_checksum] =
      artifact_parts(observation.identity.artifact_identity);
  validate_observation(observation, trading_day);
  const Json encoded = {
      {"schema_version", 1},
      {"updated_at_epoch_ms", observation.updated_at_epoch_ms},
      {"runtime_state", observation.runtime_state},
      {"partition_state", partition_state_name(observation.replay.state)},
      {"input_ring",
       {{"capacity", observation.metrics.input_capacity},
        {"occupancy", observation.metrics.input_occupancy},
        {"high_watermark", observation.metrics.input_high_watermark}}},
      {"output_ring",
       {{"capacity", observation.metrics.output_capacity},
        {"occupancy", observation.metrics.output_occupancy},
        {"high_watermark", observation.metrics.output_high_watermark}}},
      {"pending_inputs", observation.replay.pending_input_count},
      {"pending_publications", observation.replay.pending_publication_count},
      {"highest_contiguous_completed_offset",
       optional_offset(observation.replay.highest_contiguous_completed_offset)},
      {"next_commit_offset", optional_offset(observation.replay.next_commit_offset)},
      {"admission", admission_json(observation, artifact_checksum)}};
  return encoded.dump();
}

} // namespace simplematch::matching
