#include "simplematch/matching/runtime/matching_partition_runtime_driver.hpp"

#include <algorithm>
#include <chrono>
#include <deque>
#include <filesystem>
#include <memory>
#include <stdexcept>
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

RuntimeSupervisorOptions sanitizer_tolerant_supervisor_options() {
  RuntimeSupervisorOptions options;
  options.startup_timeout = std::chrono::seconds(10);
  options.output_drain_timeout = std::chrono::seconds(10);
  return options;
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

AssignedCommandRecord unknown_instrument_order_record(std::int64_t offset) {
  auto command = base_command("0198a001-0000-7000-8000-000000000003");
  auto *order = command.mutable_new_order();
  order->set_order_id("0198a001-0000-7000-8000-000000000013");
  order->set_account_id("0198a001-0000-7000-8000-0000000000aa");
  order->mutable_instrument()->set_venue_mic("XTAI");
  order->mutable_instrument()->set_symbol("9999");
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
    ++commit_calls;
    committed_offsets.push_back(next_offset);
    if (throw_on_commit_call.has_value() && commit_calls == *throw_on_commit_call) {
      throw std::runtime_error("simulated commit acknowledgement loss");
    }
  }

  std::deque<AssignedCommandRecord> records;
  std::optional<DirectKafkaPartitionAssignment> assigned;
  std::vector<std::int64_t> committed_offsets;
  std::size_t commit_calls{};
  std::optional<std::size_t> throw_on_commit_call;
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

class AsyncRecordingPublisher final : public MatchingEventPublisher {
public:
  bool publish(const MatchingEventRecord &) override { return false; }

  bool supports_async() const noexcept override { return true; }

  MatchingPublicationSubmitResult submit_async(
      const MatchingEventRecord &record, std::uint64_t publication_id) override {
    submissions.push_back({publication_id, record});
    return available ? MatchingPublicationSubmitResult::kSubmitted
                     : MatchingPublicationSubmitResult::kUnavailable;
  }

  std::optional<MatchingPublicationCompletion> next_completion() override {
    if (completions.empty()) {
      return std::nullopt;
    }
    auto completion = completions.front();
    completions.pop_front();
    return completion;
  }

  struct Submission {
    std::uint64_t publication_id;
    MatchingEventRecord record;
  };

  bool available{true};
  std::vector<Submission> submissions;
  std::deque<MatchingPublicationCompletion> completions;
};

class RetainedRecoveryConsumer final : public DirectPartitionKafkaConsumer {
public:
  void assign(const DirectKafkaPartitionAssignment &value) override { assigned = value; }

  std::optional<AssignedCommandRecord> poll() override { return std::nullopt; }

  void commit_synchronously(std::int64_t next_offset) override {
    committed_offsets.push_back(next_offset);
  }

  DirectKafkaPartitionOffsets offsets() override { return offset_range; }

  std::vector<AssignedCommandRecord> read_retained_batch(
      std::int64_t first_offset,
      std::int64_t end_offset,
      std::size_t maximum_records) override {
    ++read_batch_calls;
    maximum_requested_batch_size = std::max(maximum_requested_batch_size, maximum_records);
    std::vector<AssignedCommandRecord> batch;
    for (const auto &record : retained) {
      if (record.offset >= first_offset && record.offset < end_offset) {
        batch.push_back(record);
        if (batch.size() == maximum_records) {
          break;
        }
      }
    }
    return batch;
  }

  void seek(std::int64_t next_offset) override { seek_offsets.push_back(next_offset); }

  DirectKafkaPartitionOffsets offset_range{10, 12, 12};
  std::vector<AssignedCommandRecord> retained;
  std::optional<DirectKafkaPartitionAssignment> assigned;
  std::vector<std::int64_t> committed_offsets;
  std::vector<std::int64_t> seek_offsets;
  std::size_t read_batch_calls{};
  std::size_t maximum_requested_batch_size{};
};

TEST(MatchingPartitionRuntimeDriverTest, DrainsPendingPublicationBeforePollingAnotherCommand) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  consumer.records.push_back(order_record(12));
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

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
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

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
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  publisher.available = false;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kPublicationUnavailable);
  EXPECT_EQ(consumer.committed_offsets, std::vector<std::int64_t>{11});

  publisher.available = true;
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_EQ(consumer.committed_offsets, (std::vector<std::int64_t>{11, 12}));
}

TEST(MatchingPartitionRuntimeDriverTest, CommitsOnlyAfterAsyncDeliveryReport) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  AsyncRecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(publisher.submissions.size(), 1U);
  EXPECT_EQ(consumer.committed_offsets, std::vector<std::int64_t>{11});

  publisher.completions.push_back(
      {publisher.submissions.front().publication_id, MatchingPublicationDelivery::kPersisted});
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_EQ(consumer.committed_offsets, (std::vector<std::int64_t>{11, 12}));
}

TEST(MatchingPartitionRuntimeDriverTest, RetriesAmbiguousPublicationWithoutCommittingIt) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  AsyncRecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  const auto first_id = publisher.submissions.front().publication_id;
  publisher.completions.push_back({first_id, MatchingPublicationDelivery::kPossiblyPersisted});
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kBackpressured);
  EXPECT_EQ(consumer.committed_offsets, std::vector<std::int64_t>{11});
  ASSERT_EQ(publisher.submissions.size(), 2U);
  EXPECT_EQ(publisher.submissions.back().publication_id, first_id);
}

TEST(MatchingPartitionRuntimeDriverTest, DoesNotAcknowledgeAnOffsetWhenCommitAcknowledgementIsLost) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(order_record(11));
  consumer.throw_on_commit_call = 2;
  AsyncRecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  publisher.completions.push_back(
      {publisher.submissions.front().publication_id, MatchingPublicationDelivery::kPersisted});

  EXPECT_THROW(static_cast<void>(driver.run_once()), std::runtime_error);
  ASSERT_TRUE(replay.next_commit_offset().has_value());
  EXPECT_EQ(*replay.next_commit_offset(), 12);
}

TEST(MatchingPartitionRuntimeDriverTest, DoesNotAssignWhenOwnershipIsNotConfirmed) {
  auto permit = ready_permit();
  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));
  auto replay = PartitionReplayCoordinator(assignment(), identity(), permit, core(), 4, 32, 32, 64);
  RecordingConsumer consumer;
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  EXPECT_FALSE(driver.start());
  EXPECT_FALSE(consumer.assigned.has_value());
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kOwnershipDenied);
}

TEST(MatchingPartitionRuntimeDriverTest, PropagatesUnknownInstrumentFailureWithoutTeardownCrash) {
  auto replay = coordinator();
  RecordingConsumer consumer;
  consumer.records.push_back(open_record(10));
  consumer.records.push_back(unknown_instrument_order_record(11));
  RecordingPublisher publisher;
  auto options = sanitizer_tolerant_supervisor_options();
  options.output_drain_timeout = std::chrono::milliseconds(50);
  MatchingPartitionRuntimeDriver driver(consumer, replay, publisher, nullptr, options);

  ASSERT_TRUE(driver.start());
  ASSERT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kFailedClosed);
  EXPECT_EQ(replay.status().state, PartitionSessionState::kFailedClosed);
  EXPECT_EQ(replay.status().reason, "MATCHING_CORE_REJECTED_COMMAND");
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
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start(&store));
  EXPECT_EQ(consumer.seek_offsets, std::vector<std::int64_t>{12});
  EXPECT_EQ(consumer.read_batch_calls, 1U);
  EXPECT_EQ(consumer.maximum_requested_batch_size, 4U);
  EXPECT_TRUE(publisher.records.empty());
  EXPECT_EQ(driver.run_once(), MatchingPartitionDriverStep::kProcessed);
  EXPECT_TRUE(publisher.records.empty());
  std::filesystem::remove(path);
}

TEST(MatchingPartitionRuntimeDriverTest, ReplaysBaselineUsingBoundedKafkaBatches) {
  const auto path =
      std::filesystem::temp_directory_path() / "simplematch-driver-batched-baseline.json";
  std::filesystem::remove(path);
  PvcBaselineMetadataStore store(path.string());
  store.save(PartitionBaselineMetadata{assignment(), identity(), 10, 14});

  auto replay = coordinator();
  RetainedRecoveryConsumer consumer;
  consumer.offset_range = {10, 15, 15};
  consumer.retained = {
      open_record(10), order_record(11), order_record(12), order_record(13), order_record(14)};
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(driver.start(&store));
  EXPECT_EQ(consumer.read_batch_calls, 2U);
  EXPECT_EQ(consumer.maximum_requested_batch_size, 4U);
  EXPECT_EQ(consumer.seek_offsets, std::vector<std::int64_t>{15});
  EXPECT_TRUE(publisher.records.empty());
  std::filesystem::remove(path);
}

TEST(MatchingPartitionRuntimeDriverTest, ScansAndReplaysRetainedBatchesWithoutABaseline) {
  const auto path =
      std::filesystem::temp_directory_path() / "simplematch-driver-scan-baseline.json";
  std::filesystem::remove(path);

  auto replay = coordinator();
  RetainedRecoveryConsumer consumer;
  consumer.offset_range = {10, 12, 12};
  consumer.retained = {open_record(10), order_record(11)};
  RecordingPublisher publisher;
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());
  PvcBaselineMetadataStore store(path.string());

  ASSERT_TRUE(driver.start(&store));
  EXPECT_EQ(consumer.read_batch_calls, 2U);
  EXPECT_EQ(consumer.maximum_requested_batch_size, 4U);
  EXPECT_EQ(consumer.seek_offsets, std::vector<std::int64_t>{12});
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
  MatchingPartitionRuntimeDriver driver(
      consumer, replay, publisher, nullptr, sanitizer_tolerant_supervisor_options());

  EXPECT_FALSE(driver.start(&store));
  EXPECT_TRUE(consumer.seek_offsets.empty());
  EXPECT_TRUE(publisher.records.empty());
  std::filesystem::remove(path);
}

} // namespace
} // namespace simplematch::matching
