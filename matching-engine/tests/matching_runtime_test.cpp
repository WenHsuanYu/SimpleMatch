#include "simplematch/matching/runtime/matching_runtime.hpp"
#include "simplematch/matching/runtime/matching_runtime_supervisor.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <chrono>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <vector>

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

TEST(BoundedSpscRingTest, RequiresAPowerOfTwoCapacity) {
  EXPECT_THROW(BoundedSpscRing<std::uint64_t>(3), std::invalid_argument);
  EXPECT_NO_THROW(BoundedSpscRing<std::uint64_t>(4));
}

TEST(BoundedSpscRingTest, PreservesOrderAcrossRepeatedWrapAround) {
  BoundedSpscRing<std::uint64_t> ring(4);

  for (std::uint64_t cycle = 0; cycle < 1'000; ++cycle) {
    for (std::uint64_t index = 0; index < 4; ++index) {
      ASSERT_TRUE(ring.try_push(cycle * 4 + index));
    }
    EXPECT_FALSE(ring.try_push(0));
    for (std::uint64_t index = 0; index < 4; ++index) {
      ASSERT_EQ(ring.try_pop(), cycle * 4 + index);
    }
  }
}

TEST(BoundedSpscRingTest, PublishesOnlyCompletedValuesToTheConsumer) {
  BoundedSpscRing<std::uint64_t> ring(1);
  std::uint64_t observed = 0;
  std::thread consumer([&ring, &observed] {
    while (true) {
      const auto value = ring.try_pop();
      if (value.has_value()) {
        observed = *value;
        return;
      }
      std::this_thread::yield();
    }
  });

  while (!ring.try_push(42)) {
    std::this_thread::yield();
  }
  consumer.join();

  EXPECT_EQ(observed, 42U);
}

TEST(BoundedSpscRingTest, ConsumesAvailableValuesAsABoundedBatch) {
  BoundedSpscRing<std::uint64_t> ring(8);
  ASSERT_TRUE(ring.try_push(10));
  ASSERT_TRUE(ring.try_push(11));
  ASSERT_TRUE(ring.try_push(12));
  std::vector<std::uint64_t> observed;

  const std::size_t consumed = ring.consume_batch(2, [&observed](std::uint64_t value) {
    observed.push_back(value);
  });

  EXPECT_EQ(consumed, 2U);
  EXPECT_EQ(observed, (std::vector<std::uint64_t>{10, 11}));
  EXPECT_EQ(ring.try_pop(), 12U);
}

TEST(MatchingRuntimeTest, OutputBackpressureLeavesTheInputCommandUnconsumed) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 4, permit);
  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000003",
      "0198a001-0000-7000-8000-000000000013")));
  const RuntimeOutput occupied = RuntimeEventOutput{999, 0, CoreEvent{}};
  ASSERT_TRUE(runtime.output_ring().try_push(occupied));
  ASSERT_TRUE(runtime.output_ring().try_push(occupied));

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kOutputBackpressured);
  EXPECT_EQ(runtime.input_size(), 1U);
  ASSERT_TRUE(runtime.output_ring().try_pop().has_value());
  ASSERT_TRUE(runtime.output_ring().try_pop().has_value());

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.input_size(), 0U);
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
