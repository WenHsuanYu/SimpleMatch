#include "simplematch/matching/runtime/matching_runtime.hpp"

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

#include "simplematch/matching/runtime/matching_runtime_supervisor.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#ifdef __linux__
#include <sched.h>
#endif

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

CoreUuid uuid(std::string_view value) {
  return CoreUuid::parse(value).value();
}

CoreInstrument instrument() {
  return CoreInstrument::create("XTAI", "2330").value();
}

MatchingCommandContext context() {
  return MatchingCommandContext::create(
             "2026-08-11:aaaaaaaa", "2026-08-11-regular", "stable-least-loaded-v1", 0)
      .value();
}

CoreCommand order(std::string_view command_id, std::string_view order_id) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      instrument(),
      CoreSide::kBuy,
      ShareQuantity(100),
      FixedPrice(1'000'000),
      CoreOrderType::kLimit,
      CoreTimeInForce::kRod);
}

CoreCommand ioc_sell(std::string_view command_id, std::string_view order_id) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      instrument(),
      CoreSide::kSell,
      ShareQuantity(150),
      FixedPrice(1'000'000),
      CoreOrderType::kLimit,
      CoreTimeInForce::kIoc);
}

std::unique_ptr<DeterministicMatchingCore> core() {
  return std::make_unique<DeterministicMatchingCore>(
      std::vector<CoreInstrument>{instrument()}, 1, 0);
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

class AlwaysPinnedAffinity final : public RuntimeCpuAffinity {
public:
  bool pin_current_thread() override {
    ++pin_calls;
    return true;
  }

  int pin_calls{};
};

class RefusingAffinity final : public RuntimeCpuAffinity {
public:
  bool pin_current_thread() override { return false; }
};

// Holds the writer before process_one() and the submitter immediately before
// attempting to enqueue into a full ring, allowing a deterministic state race.
class InterleavedOwnershipPermit final : public PartitionOwnershipPermit {
 public:
  InterleavedOwnershipPermit() : owner_thread_(std::this_thread::get_id()) {}

  [[nodiscard]] std::int32_t partition_id() const override { return 0; }

  [[nodiscard]] bool allows_processing() const override {
    std::unique_lock lock(mutex_);
    const auto caller = std::this_thread::get_id();
    if (caller == owner_thread_) {
      return true;
    }
    if (caller == submitter_thread_) {
      if (++submitter_checks_ == 2) {
        submitter_blocked_ = true;
        condition_.notify_all();
        return condition_.wait_for(lock, std::chrono::seconds(3),
                                   [this] { return release_submitter_; });
      }
      return true;
    }
    writer_blocked_ = true;
    condition_.notify_all();
    condition_.wait_for(lock, std::chrono::seconds(10),
                        [this] { return release_writer_; });
    return false;
  }

  [[nodiscard]] PartitionOwnershipStatus status() const override {
    return {PartitionOwnershipState::kPermitted, {}};
  }

  void mark_submitter() {
    std::scoped_lock lock(mutex_);
    submitter_thread_ = std::this_thread::get_id();
  }

  [[nodiscard]] bool wait_for_writer() const {
    std::unique_lock lock(mutex_);
    return condition_.wait_for(lock, std::chrono::seconds(3),
                               [this] { return writer_blocked_; });
  }

  [[nodiscard]] bool wait_for_submitter() const {
    std::unique_lock lock(mutex_);
    return condition_.wait_for(lock, std::chrono::seconds(3),
                               [this] { return submitter_blocked_; });
  }

  void release_submitter() {
    std::scoped_lock lock(mutex_);
    release_submitter_ = true;
    condition_.notify_all();
  }

  void release_writer() {
    std::scoped_lock lock(mutex_);
    release_writer_ = true;
    condition_.notify_all();
  }

 private:
  const std::thread::id owner_thread_;
  mutable std::mutex mutex_;
  mutable std::condition_variable condition_;
  std::thread::id submitter_thread_{};
  mutable int submitter_checks_{};
  mutable bool writer_blocked_{};
  mutable bool submitter_blocked_{};
  bool release_submitter_{};
  bool release_writer_{};
};

bool wait_for_output(MatchingRuntimeSupervisor &supervisor, std::size_t count) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
  while (std::chrono::steady_clock::now() < deadline) {
    if (supervisor.output_size() >= count) {
      return true;
    }
    std::this_thread::yield();
  }
  return supervisor.output_size() >= count;
}

RuntimeSupervisorOptions sanitizer_tolerant_supervisor_options() {
  RuntimeSupervisorOptions options;
  options.startup_timeout = std::chrono::seconds(10);
  options.output_drain_timeout = std::chrono::seconds(10);
  return options;
}

#ifdef __linux__
TEST(RequestedCpuAffinityTest,
     RejectsCpuIdsBeyondNativeAffinitySetBeforeExpandingRange) {
  const std::string maximum = std::to_string(CPU_SETSIZE - 1);
  const std::string unsupported = std::to_string(CPU_SETSIZE);
  EXPECT_NO_THROW((RequestedCpuAffinity("0-" + maximum)));
  EXPECT_THROW((RequestedCpuAffinity("0-" + unsupported)),
               std::invalid_argument);
  EXPECT_THROW((RequestedCpuAffinity(unsupported)), std::invalid_argument);
  EXPECT_THROW((RequestedCpuAffinity("0-2147483647")), std::invalid_argument);
}
#endif

TEST(MatchingRuntimeSupervisorTest, KeepsWriterParkedUntilStartupGateIsReleased) {
  const auto permit = ready_permit();
  auto affinity = std::make_unique<AlwaysPinnedAffinity>();
  MatchingRuntime runtime(core(), 2, 4, permit);
  MatchingRuntimeSupervisor supervisor(
      runtime, permit, std::move(affinity), sanitizer_tolerant_supervisor_options());

  ASSERT_TRUE(supervisor.start());
  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kParked);
  EXPECT_FALSE(supervisor.submit(order(
      "0198a001-0000-7000-8000-000000000020",
      "0198a001-0000-7000-8000-000000000030")));

  ASSERT_TRUE(supervisor.release_startup());
  ASSERT_TRUE(supervisor.submit(order(
      "0198a001-0000-7000-8000-000000000021",
      "0198a001-0000-7000-8000-000000000031")));
  ASSERT_TRUE(wait_for_output(supervisor, 2))
      << "state=" << static_cast<int>(supervisor.state())
      << ", failure=" << supervisor.failure_reason()
      << ", input_size=" << runtime.input_size()
      << ", output_size=" << runtime.output_size();
  supervisor.shutdown(std::chrono::seconds(10));
  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kStopped);
}

TEST(MatchingRuntimeSupervisorTest, ConvertsWriterPinFailureIntoTerminalFailure) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 2, 4, permit);
  MatchingRuntimeSupervisor supervisor(
      runtime,
      permit,
      std::make_unique<RefusingAffinity>(),
      RuntimeSupervisorOptions{});

  EXPECT_FALSE(supervisor.start());
  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kFailedClosed);
  EXPECT_EQ(supervisor.failure_reason(), "MATCHING_WRITER_CPU_AFFINITY_FAILED");
}

TEST(MatchingRuntimeSupervisorTest, TerminalAlertStopsTheWriterWithoutUsingADataRing) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 2, 4, permit);
  MatchingRuntimeSupervisor supervisor(
      runtime,
      permit,
      std::make_unique<AlwaysPinnedAffinity>(),
      RuntimeSupervisorOptions{});

  ASSERT_TRUE(supervisor.start());
  ASSERT_TRUE(supervisor.release_startup());
  supervisor.fail_closed("TEST_TERMINAL_ALERT");
  ASSERT_TRUE(supervisor.wait_until_stopped(std::chrono::seconds(2)));

  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kFailedClosed);
  EXPECT_EQ(supervisor.failure_reason(), "TEST_TERMINAL_ALERT");
}

TEST(MatchingRuntimeSupervisorTest,
     ConcurrentBackpressureCannotReplaceTerminalFailure) {
  auto permit = std::make_shared<InterleavedOwnershipPermit>();
  MatchingRuntime runtime(core(), 1, 4, permit);
  ASSERT_TRUE(runtime.submit(order("0198a001-0000-7000-8000-000000000070",
                                   "0198a001-0000-7000-8000-000000000080")));
  MatchingRuntimeSupervisor supervisor(runtime, permit,
                                       std::make_unique<AlwaysPinnedAffinity>(),
                                       sanitizer_tolerant_supervisor_options());
  ASSERT_TRUE(supervisor.start());
  ASSERT_TRUE(supervisor.release_startup());
  const bool writer_blocked = permit->wait_for_writer();
  if (!writer_blocked) {
    supervisor.fail_closed("TEST_UNEXPECTED_WRITER_STATE");
    permit->release_writer();
    EXPECT_TRUE(writer_blocked);
    EXPECT_TRUE(supervisor.wait_until_stopped(std::chrono::seconds(3)));
    return;
  }

  bool accepted = false;
  std::jthread submitter([&] {
    permit->mark_submitter();
    accepted = supervisor
                   .submit(order("0198a001-0000-7000-8000-000000000071",
                                 "0198a001-0000-7000-8000-000000000081"))
                   .has_value();
  });
  const bool submitter_blocked = permit->wait_for_submitter();
  supervisor.fail_closed("TEST_TERMINAL_WHILE_SUBMITTING");
  permit->release_submitter();
  submitter.join();
  EXPECT_TRUE(submitter_blocked);
  EXPECT_FALSE(accepted);
  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kFailedClosed);

  permit->release_writer();
  ASSERT_TRUE(supervisor.wait_until_stopped(std::chrono::seconds(3)));
  EXPECT_EQ(supervisor.state(), MatchingRuntimeSupervisorState::kFailedClosed);
  EXPECT_EQ(supervisor.failure_reason(), "TEST_TERMINAL_WHILE_SUBMITTING");
}

TEST(MatchingRuntimeTest, BoundedInputRingNeverOverwritesAnUnreadCommand) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 4, permit);

  EXPECT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000001",
      "0198a001-0000-7000-8000-000000000011")));
  EXPECT_FALSE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000002",
      "0198a001-0000-7000-8000-000000000012")));
  EXPECT_EQ(runtime.input_size(), 1U);
  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.input_size(), 0U);
  EXPECT_EQ(runtime.output_size(), 2U);
  const auto event = runtime.take_output();
  const auto end = runtime.take_output();
  ASSERT_TRUE(event.has_value());
  ASSERT_TRUE(end.has_value());
  const auto &framed_event = std::get<RuntimeEventOutput>(*event);
  const auto &framed_end = std::get<RuntimeEndOfInput>(*end);
  EXPECT_EQ(framed_event.input_sequence, 0U);
  EXPECT_EQ(framed_event.output_index, 0U);
  EXPECT_EQ(framed_end.input_sequence, 0U);
  EXPECT_EQ(framed_end.output_count, 1U);

  const auto metrics = runtime.metrics();
  EXPECT_EQ(metrics.input_capacity, 1U);
  EXPECT_EQ(metrics.input_occupancy, 0U);
  EXPECT_EQ(metrics.input_high_watermark, 1U);
  EXPECT_EQ(metrics.output_capacity, 4U);
  EXPECT_EQ(metrics.output_occupancy, 0U);
  EXPECT_EQ(metrics.output_high_watermark, 2U);
}

TEST(MatchingRuntimeTest, DoesNotConsumeInputSequenceWhenTheRingIsFull) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 4, permit);

  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000007",
      "0198a001-0000-7000-8000-000000000017")));
  EXPECT_FALSE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000008",
      "0198a001-0000-7000-8000-000000000018")));

  ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  ASSERT_TRUE(runtime.take_output().has_value());
  ASSERT_TRUE(runtime.take_output().has_value());

  const auto sequence = runtime.submit(order(
      "0198a001-0000-7000-8000-000000000009",
      "0198a001-0000-7000-8000-000000000019"));
  ASSERT_TRUE(sequence.has_value());
  EXPECT_EQ(*sequence, 1U);
}

TEST(MatchingRuntimeTest, FramesConsecutiveInputsAndAMultiEventBurst) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 2, 4, permit);
  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000005",
      "0198a001-0000-7000-8000-000000000015")));
  ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  ASSERT_TRUE(runtime.take_output().has_value());
  const auto first_end = runtime.take_output();
  ASSERT_TRUE(first_end.has_value());
  EXPECT_EQ(std::get<RuntimeEndOfInput>(*first_end).input_sequence, 0U);

  ASSERT_TRUE(runtime.submit(ioc_sell(
      "0198a001-0000-7000-8000-000000000006",
      "0198a001-0000-7000-8000-000000000016")));
  ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  const auto first_event = runtime.take_output();
  const auto second_event = runtime.take_output();
  const auto second_end = runtime.take_output();

  ASSERT_TRUE(first_event.has_value());
  ASSERT_TRUE(second_event.has_value());
  ASSERT_TRUE(second_end.has_value());
  const auto &first = std::get<RuntimeEventOutput>(*first_event);
  const auto &second = std::get<RuntimeEventOutput>(*second_event);
  const auto &end = std::get<RuntimeEndOfInput>(*second_end);
  EXPECT_EQ(first.input_sequence, 1U);
  EXPECT_EQ(first.output_index, 0U);
  EXPECT_EQ(second.input_sequence, 1U);
  EXPECT_EQ(second.output_index, 1U);
  EXPECT_EQ(end.input_sequence, 1U);
  EXPECT_EQ(end.output_count, 2U);
}

TEST(MatchingRuntimeTest, RejectsAnOutputRingThatCannotHoldOneWorstCaseFrame) {
  const auto permit = ready_permit();

  EXPECT_THROW(MatchingRuntime(core(), 1, 2, permit), std::invalid_argument);
}

TEST(MatchingRuntimeTest, OutputBackpressureLeavesTheInputCommandUnconsumed) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 4, permit);
  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000003",
      "0198a001-0000-7000-8000-000000000013")));
  ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.output_size(), 2U);

  ASSERT_TRUE(runtime.submit(CoreCommand::open(
      context(), uuid("0198a001-0000-7000-8000-000000000004"), 15)));

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kOutputBackpressured);
  EXPECT_EQ(runtime.input_size(), 1U);
  ASSERT_TRUE(runtime.take_output().has_value());
  ASSERT_TRUE(runtime.take_output().has_value());

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.input_size(), 0U);
  const auto end = runtime.take_output();
  ASSERT_TRUE(end.has_value());
  ASSERT_TRUE(std::holds_alternative<RuntimeEndOfInput>(*end));
  EXPECT_EQ(std::get<RuntimeEndOfInput>(*end).output_count, 0U);
}

TEST(MatchingRuntimeTest, SelfFencedPermitLeavesQueuedCommandsUnread) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 4, permit);
  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000004",
      "0198a001-0000-7000-8000-000000000014")));

  permit->report_renewal_uncertainty(std::chrono::steady_clock::time_point{});
  permit->evaluate_at(std::chrono::steady_clock::time_point{} + std::chrono::seconds(5));

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kOwnershipDenied);
  EXPECT_EQ(runtime.input_size(), 1U);
  EXPECT_EQ(runtime.output_size(), 0U);
}

} // namespace
} // namespace simplematch::matching
