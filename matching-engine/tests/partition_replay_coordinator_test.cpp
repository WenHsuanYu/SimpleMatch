#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <chrono>
#include <filesystem>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "matching_runtime_v1.pb.h"

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::string_view kArtifactIdentity =
    "2026-08-11:7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kImageDigest =
    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

DirectKafkaPartitionAssignment assignment() {
  return {"matching.commands", 0};
}

PinnedMatchingIdentity identity() {
  return {std::string(kArtifactIdentity),
          "2026-08-11-regular",
          "stable-least-loaded-v1",
          std::string(kImageDigest),
          1,
          1,
          1};
}

std::shared_ptr<LeaseFencedPartitionOwnershipPermit> ready_permit() {
  auto permit = std::make_shared<LeaseFencedPartitionOwnershipPermit>(
      PartitionOwnershipIdentity{0, "matching-0:pod-uid-123", "2026-08-11-regular"},
      std::chrono::seconds(5));
  EXPECT_TRUE(permit->confirm_renewal(
      PartitionOwnershipIdentity{0, "matching-0:pod-uid-123", "2026-08-11-regular"},
      std::chrono::steady_clock::time_point{}));
  return permit;
}

CoreInstrument instrument() {
  return CoreInstrument::create("XTAI", "2330").value();
}

std::unique_ptr<DeterministicMatchingCore> core() {
  return std::make_unique<DeterministicMatchingCore>(
      std::vector<CoreInstrument>{instrument()}, 8, 0);
}

PartitionReplayCoordinator coordinator(
    std::shared_ptr<const PartitionOwnershipPermit> permit = ready_permit()) {
  return PartitionReplayCoordinator(assignment(), identity(), std::move(permit), core(), 4, 32, 32, 64);
}

simplematch::matching::runtime::v1::MatchingCommand base_command(std::string_view command_id) {
  simplematch::matching::runtime::v1::MatchingCommand command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id(std::string(command_id));
  header->set_trading_session_id("2026-08-11-regular");
  header->set_partition_id(0);
  header->mutable_artifact_identity()->set_trading_day("2026-08-11");
  header->mutable_artifact_identity()->set_content_sha256(
      "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943");
  header->set_routing_algorithm_version("stable-least-loaded-v1");
  return command;
}

AssignedCommandRecord open_record(std::int64_t offset) {
  auto command = base_command("0198a001-0000-7000-8000-000000000001");
  command.mutable_open_barrier()->set_expected_partition_count(15);
  command.mutable_open_barrier()->set_event_schema_version(1);
  command.mutable_open_barrier()->set_event_identity_version(1);
  command.mutable_open_barrier()->set_matching_image_digest(std::string(kImageDigest));
  return {assignment(), offset, command.header().command_id(), command.SerializeAsString()};
}

AssignedCommandRecord order_record(
    std::int64_t offset,
    std::string_view command_id,
    std::string_view order_id,
    simplematch::common::v2::Side side) {
  auto command = base_command(command_id);
  auto *order = command.mutable_new_order();
  order->set_order_id(std::string(order_id));
  order->set_account_id("0198a001-0000-7000-8000-0000000000aa");
  order->mutable_instrument()->set_venue_mic("XTAI");
  order->mutable_instrument()->set_symbol("2330");
  order->set_side(side);
  order->set_quantity_shares(100);
  order->set_limit_price_units(1'000'000);
  order->set_order_type(simplematch::common::v2::ORDER_TYPE_LIMIT);
  order->set_time_in_force(simplematch::common::v2::TIME_IN_FORCE_ROD);
  return {assignment(), offset, command.header().command_id(), command.SerializeAsString()};
}

TEST(PartitionReplayCoordinatorTest, RequiresOnePinnedOpenBarrierBeforeOrders) {
  auto runtime = coordinator();

  EXPECT_EQ(
      runtime.ingest(order_record(
          10,
          "0198a001-0000-7000-8000-000000000002",
          "0198a001-0000-7000-8000-000000000012",
          simplematch::common::v2::SIDE_BUY)),
      PartitionReplayResult::kFailedClosed);
  EXPECT_EQ(runtime.status().state, PartitionSessionState::kFailedClosed);
}

TEST(PartitionReplayCoordinatorTest, AcknowledgesOutputsBeforeAdvancingTheContiguousWatermark) {
  auto runtime = coordinator();
  const auto open = open_record(10);
  const auto sell = order_record(
      11,
      "0198a001-0000-7000-8000-000000000002",
      "0198a001-0000-7000-8000-000000000012",
      simplematch::common::v2::SIDE_SELL);
  const auto buy = order_record(
      12,
      "0198a001-0000-7000-8000-000000000003",
      "0198a001-0000-7000-8000-000000000013",
      simplematch::common::v2::SIDE_BUY);

  ASSERT_EQ(runtime.ingest(open), PartitionReplayResult::kAccepted);
  ASSERT_EQ(runtime.next_commit_offset(), 11);
  ASSERT_TRUE(runtime.acknowledge_commit(11));
  ASSERT_EQ(runtime.ingest(sell), PartitionReplayResult::kAccepted);
  ASSERT_EQ(runtime.ingest(buy), PartitionReplayResult::kAccepted);

  EXPECT_EQ(runtime.acknowledge_published(12, 0), PartitionReplayResult::kAccepted);
  EXPECT_FALSE(runtime.next_commit_offset().has_value());
  EXPECT_EQ(runtime.acknowledge_published(11, 0), PartitionReplayResult::kAccepted);
  EXPECT_EQ(runtime.next_commit_offset(), 13);
}

TEST(PartitionReplayCoordinatorTest, RebuildsFromTheRetainedBarrierWithoutRepublishingCommittedEffects) {
  auto first = coordinator();
  const auto open = open_record(20);
  const auto sell = order_record(
      21,
      "0198a001-0000-7000-8000-000000000004",
      "0198a001-0000-7000-8000-000000000014",
      simplematch::common::v2::SIDE_SELL);
  ASSERT_EQ(first.ingest(open), PartitionReplayResult::kAccepted);
  ASSERT_TRUE(first.acknowledge_commit(21));
  ASSERT_EQ(first.ingest(sell), PartitionReplayResult::kAccepted);
  ASSERT_EQ(first.acknowledge_published(21, 0), PartitionReplayResult::kAccepted);
  ASSERT_TRUE(first.acknowledge_commit(22));
  const auto baseline = first.baseline_metadata();
  ASSERT_TRUE(baseline.has_value());

  auto restarted = coordinator();
  const std::vector<AssignedCommandRecord> retained{open, sell};
  ASSERT_EQ(restarted.recover(*baseline, 20, retained), PartitionReplayResult::kRecovered);
  EXPECT_FALSE(restarted.next_unacknowledged_publication().has_value());

  ASSERT_EQ(
      restarted.ingest(order_record(
          22,
          "0198a001-0000-7000-8000-000000000005",
          "0198a001-0000-7000-8000-000000000015",
          simplematch::common::v2::SIDE_BUY)),
      PartitionReplayResult::kAccepted);
  ASSERT_TRUE(restarted.next_unacknowledged_publication().has_value());
  EXPECT_EQ(restarted.next_unacknowledged_publication()->source_input_offset, 22);

  auto missing = coordinator();
  EXPECT_EQ(
      missing.recover(*baseline, 22, retained),
      PartitionReplayResult::kMissingRetainedOpenBarrier);
}

TEST(PartitionReplayCoordinatorTest, PersistsOnlyBoundedBaselineCoordinatesOnThePvc) {
  const auto path = std::filesystem::temp_directory_path() / "simplematch-partition-baseline.json";
  std::filesystem::remove(path);
  PvcBaselineMetadataStore store(path.string());
  const PartitionBaselineMetadata expected{assignment(), identity(), 100, 150};

  store.save(expected);

  EXPECT_EQ(store.load(), expected);
  std::filesystem::remove(path);
}

class RecordingDirectConsumer final : public DirectPartitionKafkaConsumer {
public:
  void assign(const DirectKafkaPartitionAssignment &assignment) override {
    ++assign_calls;
    assigned = assignment;
  }

  std::optional<AssignedCommandRecord> poll() override {
    ++poll_calls;
    return std::nullopt;
  }

  void commit_synchronously(std::int64_t next_offset) override {
    ++commit_calls;
    committed_offset = next_offset;
  }

  int assign_calls{};
  int poll_calls{};
  int commit_calls{};
  std::optional<DirectKafkaPartitionAssignment> assigned;
  std::optional<std::int64_t> committed_offset;
};

TEST(PartitionReplayCoordinatorTest, DoesNotAssignOrPollWhenThePermitIsSelfFenced) {
  const auto permit = ready_permit();
  auto runtime = coordinator(permit);
  RecordingDirectConsumer consumer;
  DirectPartitionRuntimeDriver driver(consumer, runtime);

  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));

  EXPECT_FALSE(driver.start());
  EXPECT_EQ(driver.poll_once(), PartitionReplayResult::kOwnershipDenied);
  EXPECT_FALSE(driver.commit_completed_synchronously());
  EXPECT_EQ(consumer.assign_calls, 0);
  EXPECT_EQ(consumer.poll_calls, 0);
  EXPECT_EQ(consumer.commit_calls, 0);
}

TEST(PartitionReplayCoordinatorTest, DoesNotReleasePendingPublicationWhenThePermitIsSelfFenced) {
  const auto permit = ready_permit();
  auto runtime = coordinator(permit);
  ASSERT_EQ(runtime.ingest(open_record(10)), PartitionReplayResult::kAccepted);
  ASSERT_TRUE(runtime.acknowledge_commit(11));
  ASSERT_EQ(
      runtime.ingest(order_record(
          11,
          "0198a001-0000-7000-8000-000000000006",
          "0198a001-0000-7000-8000-000000000016",
          simplematch::common::v2::SIDE_SELL)),
      PartitionReplayResult::kAccepted);
  ASSERT_TRUE(runtime.next_unacknowledged_publication().has_value());

  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));

  EXPECT_FALSE(runtime.next_unacknowledged_publication().has_value());
  EXPECT_EQ(runtime.continue_processing(), PartitionReplayResult::kOwnershipDenied);
  EXPECT_EQ(runtime.status().ownership.state, PartitionOwnershipState::kSelfFenced);
}

TEST(PartitionReplayCoordinatorTest, DoesNotScanRetainedRecordsWhenThePermitIsSelfFenced) {
  const auto permit = ready_permit();
  auto runtime = coordinator(permit);
  const std::vector<AssignedCommandRecord> retained{{assignment(), 10, "", "not-a-command"}};

  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));

  EXPECT_EQ(
      runtime.recover_from_retained_records(10, 10, retained),
      PartitionReplayResult::kOwnershipDenied);
}

} // namespace
} // namespace simplematch::matching
