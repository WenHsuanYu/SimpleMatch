#include "simplematch/matching/runtime/matching_runtime.hpp"
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
  EXPECT_EQ(event->kind, RuntimeOutputKind::kEvent);
  EXPECT_EQ(event->input_sequence, 0U);
  EXPECT_EQ(event->output_index, 0U);
  EXPECT_EQ(end->kind, RuntimeOutputKind::kEndOfInput);
  EXPECT_EQ(end->input_sequence, 0U);
  EXPECT_EQ(end->output_count, 1U);
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
  const RuntimeOutput occupied{
      RuntimeOutputKind::kEvent, 999, 0, 0, CoreEvent{}};
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
