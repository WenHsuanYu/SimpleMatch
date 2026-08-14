#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"

#include <chrono>
#include <deque>
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

std::unique_ptr<DeterministicMatchingCore> core() {
  return std::make_unique<DeterministicMatchingCore>(
      std::vector<CoreInstrument>{CoreInstrument::create("XTAI", "2330").value()}, 8, 0);
}

PartitionReplayCoordinator coordinator() {
  return PartitionReplayCoordinator(
      assignment(), identity(), ready_permit(), core(), 4, 32, 32, 64);
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

AssignedCommandRecord order_record(std::int64_t offset) {
  auto command = base_command("0198a001-0000-7000-8000-000000000002");
  auto *order = command.mutable_new_order();
  order->set_order_id("0198a001-0000-7000-8000-000000000012");
  order->set_account_id("0198a001-0000-7000-8000-0000000000aa");
  order->mutable_instrument()->set_venue_mic("XTAI");
  order->mutable_instrument()->set_symbol("2330");
  order->set_side(simplematch::common::v2::SIDE_SELL);
  order->set_quantity_shares(100);
  order->set_limit_price_units(1'000'000);
  order->set_order_type(simplematch::common::v2::ORDER_TYPE_LIMIT);
  order->set_time_in_force(simplematch::common::v2::TIME_IN_FORCE_ROD);
  return {assignment(), offset, command.header().command_id(), command.SerializeAsString()};
}

class RecordingConsumer final : public DirectPartitionKafkaConsumer {
public:
  void assign(const DirectKafkaPartitionAssignment &value) override {
    assigned = value;
  }

  std::optional<AssignedCommandRecord> poll() override {
    if (records.empty()) {
      return std::nullopt;
    }
    AssignedCommandRecord record = std::move(records.front());
    records.pop_front();
    return record;
  }

  void commit_synchronously(std::int64_t next_offset) override {
    committed_offsets.push_back(next_offset);
  }

  std::deque<AssignedCommandRecord> records;
  std::optional<DirectKafkaPartitionAssignment> assigned;
  std::vector<std::int64_t> committed_offsets;
};

class RecordingPublisher final : public MatchingEventPublisher {
public:
  bool publish(const MatchingEventRecord &record) override {
    if (!available) {
      return false;
    }
    records.push_back(record);
    return true;
  }

  bool available{true};
  std::vector<MatchingEventRecord> records;
};

class RetainedRecoveryConsumer final : public DirectPartitionKafkaConsumer {
public:
  void assign(const DirectKafkaPartitionAssignment &value) override { assigned = value; }

  std::optional<AssignedCommandRecord> poll() override { return std::nullopt; }

  void commit_synchronously(std::int64_t next_offset) override {
    committed_offsets.push_back(next_offset);
  }

  DirectKafkaPartitionOffsets offsets() override { return offset_range; }

  std::vector<AssignedCommandRecord> read_retained(
      std::int64_t first_offset, std::int64_t end_offset) override {
    EXPECT_EQ(first_offset, retained.front().offset);
    EXPECT_EQ(end_offset, retained.back().offset + 1);
    return retained;
  }

  void seek(std::int64_t next_offset) override { seek_offsets.push_back(next_offset); }

  DirectKafkaPartitionOffsets offset_range{10, 12, 12};
  std::vector<AssignedCommandRecord> retained;
  std::optional<DirectKafkaPartitionAssignment> assigned;
  std::vector<std::int64_t> committed_offsets;
  std::vector<std::int64_t> seek_offsets;
};

TEST(MatchingPartitionRuntimeDriverTest, DrainsPendingPublicationBeforePollingAnotherCommand) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  consumer.records.push_back(order_record(12));
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  publisher.available = false;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kPublicationUnavailable);
  EXPECT_EQ(consumer.records.size(), 1U);

  publisher.available = true;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_EQ(consumer.records.size(), 0U);
}

TEST(MatchingPartitionRuntimeDriverTest, PublishesBeforeCommittingAnInputOffset) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  ASSERT_TRUE(driver.start());
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(consumer.committed_offsets, std::vector<std::int64_t>{11});

  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(publisher.records.size(), 1U);
  EXPECT_EQ(publisher.records.front().source_input_offset, 11);
  EXPECT_EQ(consumer.committed_offsets, (std::vector<std::int64_t>{11, 12}));
}

TEST(MatchingPartitionRuntimeDriverTest, LeavesPublicationAndCommitPendingWhenPublisherFails) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  publisher.available = false;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kPublicationUnavailable);
  EXPECT_EQ(consumer.committed_offsets, std::vector<std::int64_t>{11});

  publisher.available = true;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_EQ(consumer.committed_offsets, (std::vector<std::int64_t>{11, 12}));
}

TEST(MatchingPartitionRuntimeDriverTest, DoesNotAssignWhenOwnershipIsNotConfirmed) {
  auto permit = ready_permit();
  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));
  auto replay = PartitionReplayCoordinator(assignment(), identity(), permit, core(), 4, 32, 32, 64);
  RecordingConsumer consumer;
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  EXPECT_FALSE(driver.start());
  EXPECT_FALSE(consumer.assigned.has_value());
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kOwnershipDenied);
}

TEST(MatchingPartitionRuntimeDriverTest, ReplaysThePvcBaselineBeforeLivePolling) {
  const auto path =
      std::filesystem::temp_directory_path() / "simplematch-driver-baseline.json";
  std::filesystem::remove(path);
  PvcBaselineMetadataStore store(path.string());
  store.save(PartitionBaselineMetadata{assignment(), identity(), 10, 11});

  auto replay = coordinator();
  RetainedRecoveryConsumer consumer;
  consumer.retained = {open_record(10), order_record(11)};
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  ASSERT_TRUE(driver.start(&store));
  EXPECT_EQ(consumer.seek_offsets, std::vector<std::int64_t>{12});
  EXPECT_TRUE(publisher.records.empty());
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_TRUE(publisher.records.empty());
  std::filesystem::remove(path);
}

TEST(MatchingPartitionRuntimeDriverTest, RejectsBaselineAheadOfKafkaCommitWatermark) {
  const auto path =
      std::filesystem::temp_directory_path() / "simplematch-driver-ahead-baseline.json";
  std::filesystem::remove(path);
  PvcBaselineMetadataStore store(path.string());
  store.save(PartitionBaselineMetadata{assignment(), identity(), 10, 11});

  auto replay = coordinator();
  RetainedRecoveryConsumer consumer;
  consumer.offset_range = {10, 12, 11};
  consumer.retained = {open_record(10), order_record(11)};
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher);

  EXPECT_FALSE(driver.start(&store));
  EXPECT_TRUE(consumer.seek_offsets.empty());
  EXPECT_TRUE(publisher.records.empty());
  std::filesystem::remove(path);
}

} // namespace
} // namespace simplematch::matching
