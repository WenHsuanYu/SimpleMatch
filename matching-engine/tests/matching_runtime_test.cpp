#include "simplematch/matching/runtime/matching_runtime.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <chrono>
#include <memory>
#include <string_view>
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
  MatchingRuntime runtime(core(), 1, 3, permit);

  EXPECT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000001",
      "0198a001-0000-7000-8000-000000000011")));
  EXPECT_FALSE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000002",
      "0198a001-0000-7000-8000-000000000012")));
  EXPECT_EQ(runtime.input_size(), 1U);
  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.input_size(), 0U);
  EXPECT_EQ(runtime.output_size(), 1U);
}

TEST(MatchingRuntimeTest, OutputBackpressureLeavesTheInputCommandUnconsumed) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 3, permit);
  ASSERT_TRUE(runtime.submit(order(
      "0198a001-0000-7000-8000-000000000003",
      "0198a001-0000-7000-8000-000000000013")));
  ASSERT_TRUE(runtime.output_ring().try_push(CoreEvent{}));
  ASSERT_TRUE(runtime.output_ring().try_push(CoreEvent{}));

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kOutputBackpressured);
  EXPECT_EQ(runtime.input_size(), 1U);
  ASSERT_TRUE(runtime.output_ring().try_pop().has_value());

  EXPECT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
  EXPECT_EQ(runtime.input_size(), 0U);
}

TEST(MatchingRuntimeTest, SelfFencedPermitLeavesQueuedCommandsUnread) {
  const auto permit = ready_permit();
  MatchingRuntime runtime(core(), 1, 3, permit);
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
