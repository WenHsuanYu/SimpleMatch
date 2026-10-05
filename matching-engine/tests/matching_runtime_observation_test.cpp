#include "simplematch/matching/runtime/matching_runtime_observation.hpp"

#include <gtest/gtest.h>
#include <nlohmann/json.hpp>

#include <chrono>

namespace simplematch::matching {
namespace {

MatchingRuntimeObservation valid_observation() {
  const std::string artifact_checksum(64, 'a');
  const std::string matching_image_digest = "sha256:" + std::string(64, 'b');
  return MatchingRuntimeObservation{
      .partition_id = 4,
      .owner_id = "matching-4:pod-uid",
      .artifact_id = "market-reference-2026-10-05",
      .identity =
          PinnedMatchingIdentity{
              .artifact_identity = "2026-10-05:" + artifact_checksum,
              .trading_session_id = "2026-10-05-regular",
              .routing_algorithm_version = "stable-least-loaded-v1",
              .matching_image_digest = matching_image_digest},
      .metrics = MatchingRuntimeMetrics{.input_capacity = 8, .output_capacity = 16},
      .replay =
          PartitionReplayStatus{
              .state = PartitionSessionState::kOpen,
              .ownership = {PartitionOwnershipState::kPermitted, "LEASE_RENEWED"},
              .highest_contiguous_completed_offset = 6,
              .next_commit_offset = 7,
              .pending_input_count = 0,
              .pending_publication_count = 0,
              .reason = "READY"},
      .runtime_state = "READY",
      .updated_at_epoch_ms = 1'759'626'000'000};
}

TEST(MatchingRuntimeObservationTest, ExposesIdentityOwnershipRecoveryAndProgress) {
  const auto observation = valid_observation();

  const auto encoded = nlohmann::json::parse(encode_matching_runtime_observation(observation));
  const auto &admission = encoded.at("admission");
  const auto &identity = admission.at("identity");

  EXPECT_EQ(admission.at("partition_id"), 4);
  EXPECT_EQ(admission.at("owner_id"), "matching-4:pod-uid");
  EXPECT_EQ(identity.at("artifact").at("id"), "market-reference-2026-10-05");
  EXPECT_EQ(identity.at("artifact").at("content_sha256"), std::string(64, 'a'));
  EXPECT_EQ(identity.at("trading_session_id"), "2026-10-05-regular");
  EXPECT_EQ(identity.at("matching_algorithm_version"), "stable-least-loaded-v1");
  EXPECT_TRUE(admission.at("ownership_permitted"));
  EXPECT_TRUE(admission.at("recovery_complete"));
  EXPECT_EQ(encoded.at("next_commit_offset"), 7);
  EXPECT_FALSE(encoded.contains("artifact_id"));
}

TEST(MatchingRuntimeObservationTest, PreservesSelfFencingDespiteACachedReadyState) {
  auto observation = valid_observation();
  observation.replay.ownership.state = PartitionOwnershipState::kSelfFenced;

  const auto encoded = nlohmann::json::parse(encode_matching_runtime_observation(observation));

  EXPECT_EQ(encoded.at("runtime_state"), "READY");
  EXPECT_FALSE(encoded.at("admission").at("ownership_permitted"));
}

TEST(MatchingRuntimeObservationTest, ReportsUnconfirmedOwnershipDuringProcessingGrace) {
  auto observation = valid_observation();
  const PartitionOwnershipIdentity identity{
      observation.partition_id, observation.owner_id, observation.identity.trading_session_id};
  LeaseFencedPartitionOwnershipPermit permit(identity, std::chrono::seconds{5});
  const auto renewed_at = std::chrono::steady_clock::time_point{};
  ASSERT_TRUE(permit.confirm_renewal(identity, renewed_at));
  permit.report_renewal_uncertainty(renewed_at + std::chrono::seconds{1});
  ASSERT_TRUE(permit.allows_processing());
  observation.replay.ownership = permit.status();

  const auto encoded = nlohmann::json::parse(encode_matching_runtime_observation(observation));

  EXPECT_EQ(encoded.at("runtime_state"), "READY");
  EXPECT_FALSE(encoded.at("admission").at("ownership_permitted"));
  EXPECT_TRUE(encoded.at("admission").at("recovery_complete"));
  permit.evaluate_at(renewed_at + std::chrono::seconds{6});
  EXPECT_FALSE(permit.allows_processing());
}

TEST(MatchingRuntimeObservationTest, PreservesIncompleteRecoveryDespiteACachedReadyState) {
  auto observation = valid_observation();
  observation.replay.state = PartitionSessionState::kAwaitingOpen;

  const auto encoded = nlohmann::json::parse(encode_matching_runtime_observation(observation));

  EXPECT_EQ(encoded.at("runtime_state"), "READY");
  EXPECT_FALSE(encoded.at("admission").at("recovery_complete"));
}

TEST(MatchingRuntimeObservationTest, RejectsMalformedPinnedIdentity) {
  auto observation = valid_observation();
  observation.identity.artifact_identity = "2026-10-05:not-a-checksum";

  EXPECT_THROW(encode_matching_runtime_observation(observation), std::invalid_argument);
}

} // namespace
} // namespace simplematch::matching
