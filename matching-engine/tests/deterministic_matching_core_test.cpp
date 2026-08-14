#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "matching_test_support.hpp"

#include <algorithm>
#include <array>
#include <string_view>
#include <vector>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

using test_support::context;
using test_support::instrument;
using test_support::uuid;

CoreCommand new_order(
    std::string_view command_id,
    std::string_view order_id,
    CoreSide side,
    std::int64_t quantity,
    std::int64_t price,
    CoreTimeInForce tif = CoreTimeInForce::kRod,
    CoreOrderType order_type = CoreOrderType::kLimit) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      instrument(),
      side,
      ShareQuantity(quantity),
      FixedPrice(price),
      order_type,
      tif);
}

DeterministicMatchingCore core() {
  return DeterministicMatchingCore({instrument()}, 16, 0);
}

TEST(DeterministicMatchingCoreTest, AppliesPriceTimePriorityAndEmitsStableMakerTakerTrade) {
  auto first = core();
  auto replay = core();
  const auto sell = new_order(
      "0198a001-0000-7000-8000-000000000001",
      "0198a001-0000-7000-8000-000000000011",
      CoreSide::kSell,
      100,
      1'000'000);
  const auto buy = new_order(
      "0198a001-0000-7000-8000-000000000002",
      "0198a001-0000-7000-8000-000000000012",
      CoreSide::kBuy,
      100,
      1'000'000);

  ASSERT_EQ(first.process(sell), MatchingProcessResult::kApplied);
  ASSERT_EQ(replay.process(sell), MatchingProcessResult::kApplied);
  ASSERT_EQ(first.events().size(), 1U);
  EXPECT_EQ(first.events().front().type, CoreEventType::kOrderRested);

  ASSERT_EQ(first.process(buy), MatchingProcessResult::kApplied);
  ASSERT_EQ(replay.process(buy), MatchingProcessResult::kApplied);

  ASSERT_EQ(first.events().size(), 1U);
  const CoreEvent &trade = first.events().front();
  EXPECT_EQ(trade.type, CoreEventType::kTradeExecuted);
  EXPECT_EQ(trade.maker_order_id, sell.new_order().order_id);
  EXPECT_EQ(trade.taker_order_id, buy.new_order().order_id);
  EXPECT_EQ(trade.maker_account_id, sell.new_order().account_id);
  EXPECT_EQ(trade.taker_account_id, buy.new_order().account_id);
  EXPECT_EQ(trade.maker_cumulative_quantity.value(), 100);
  EXPECT_EQ(trade.taker_leaves_quantity.value(), 0);
  EXPECT_EQ(trade.output_index, 0);
  EXPECT_EQ(trade.match_index, 0);
  EXPECT_EQ(trade.quantity.value(), 100);
  EXPECT_EQ(trade.price.value(), 1'000'000);
  EXPECT_EQ(first.events().size(), replay.events().size());
  EXPECT_TRUE(std::equal(
      first.events().begin(), first.events().end(), replay.events().begin()));
}

TEST(DeterministicMatchingCoreTest, IocNeverRestsAndFokNeverPartiallyFills) {
  auto engine = core();
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000003",
          "0198a001-0000-7000-8000-000000000013",
          CoreSide::kSell,
          100,
          1'000'000)),
      MatchingProcessResult::kApplied);

  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000004",
          "0198a001-0000-7000-8000-000000000014",
          CoreSide::kBuy,
          150,
          1'000'000,
          CoreTimeInForce::kIoc)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(engine.events().size(), 2U);
  EXPECT_EQ(engine.events()[0].type, CoreEventType::kTradeExecuted);
  EXPECT_EQ(engine.events()[0].output_index, 0);
  EXPECT_EQ(engine.events()[0].match_index, 0);
  EXPECT_EQ(engine.events()[1].type, CoreEventType::kOrderCancelled);
  EXPECT_EQ(engine.events()[1].output_index, 1);
  EXPECT_EQ(engine.events()[1].leaves_quantity.value(), 50);
  EXPECT_EQ(engine.resting_order_count(instrument()), 0U);

  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000005",
          "0198a001-0000-7000-8000-000000000015",
          CoreSide::kSell,
          100,
          1'000'000)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000006",
          "0198a001-0000-7000-8000-000000000016",
          CoreSide::kBuy,
          150,
          1'000'000,
          CoreTimeInForce::kFok)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(engine.events().size(), 1U);
  EXPECT_EQ(engine.events().front().type, CoreEventType::kOrderCancelled);
  EXPECT_EQ(engine.events().front().cancellation_reason, CoreCancellationReason::kFokNotFilled);
  EXPECT_EQ(engine.resting_order_count(instrument()), 1U);
}

TEST(DeterministicMatchingCoreTest, MarketOrderConsumesBestAvailableLiquidityWithoutResting) {
  auto engine = core();
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000020",
          "0198a001-0000-7000-8000-000000000030",
          CoreSide::kSell,
          100,
          1'000'000)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000021",
          "0198a001-0000-7000-8000-000000000031",
          CoreSide::kSell,
          100,
          1'010'000)),
      MatchingProcessResult::kApplied);

  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000022",
          "0198a001-0000-7000-8000-000000000032",
          CoreSide::kBuy,
          150,
          0,
          CoreTimeInForce::kIoc,
          CoreOrderType::kMarket)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(engine.events().size(), 2U);
  EXPECT_EQ(engine.events()[0].type, CoreEventType::kTradeExecuted);
  EXPECT_EQ(engine.events()[0].price.value(), 1'000'000);
  EXPECT_EQ(engine.events()[0].quantity.value(), 100);
  EXPECT_EQ(engine.events()[1].type, CoreEventType::kTradeExecuted);
  EXPECT_EQ(engine.events()[1].price.value(), 1'010'000);
  EXPECT_EQ(engine.events()[1].quantity.value(), 50);
  EXPECT_EQ(engine.resting_order_count(instrument()), 1U);
}

TEST(DeterministicMatchingCoreTest, RejectsUnknownPartitionAndRetainsNoDynamicBookExpansion) {
  auto engine = core();
  auto wrong_partition = new_order(
      "0198a001-0000-7000-8000-000000000007",
      "0198a001-0000-7000-8000-000000000017",
      CoreSide::kBuy,
      100,
      1'000'000);
  wrong_partition.context.partition_id = 1;

  EXPECT_EQ(engine.process(wrong_partition), MatchingProcessResult::kPartitionMismatch);
  EXPECT_EQ(engine.process(CoreCommand::close(context())), MatchingProcessResult::kApplied);
  EXPECT_TRUE(engine.events().empty());
}

TEST(DeterministicMatchingCoreTest, StateChecksumChangesWithOrderBookState) {
  auto engine = core();
  const std::uint64_t empty_checksum = engine.deterministic_state_checksum();
  const auto resting = new_order(
      "0198a001-0000-7000-8000-000000000008",
      "0198a001-0000-7000-8000-000000000018",
      CoreSide::kBuy,
      100,
      1'000'000);

  ASSERT_EQ(engine.process(resting), MatchingProcessResult::kApplied);
  const std::uint64_t resting_checksum = engine.deterministic_state_checksum();
  EXPECT_NE(resting_checksum, empty_checksum);

  ASSERT_EQ(
      engine.process(CoreCommand::cancel(
          context(),
          uuid("0198a001-0000-7000-8000-000000000009"),
          resting.new_order().order_id,
          resting.new_order().account_id,
          instrument(),
          CoreSide::kBuy)),
      MatchingProcessResult::kApplied);
  EXPECT_NE(engine.deterministic_state_checksum(), resting_checksum);
}

TEST(DeterministicMatchingCoreTest, RejectsOrderBookCapacityWithoutExpandingTheBook) {
  auto engine = DeterministicMatchingCore({instrument()}, 1, 0);

  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000018",
          "0198a001-0000-7000-8000-000000000028",
          CoreSide::kBuy,
          100,
          1'000'000)),
      MatchingProcessResult::kApplied);
  EXPECT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000019",
          "0198a001-0000-7000-8000-000000000029",
          CoreSide::kBuy,
          100,
          990'000)),
      MatchingProcessResult::kOrderBookFull);
  EXPECT_EQ(engine.resting_order_count(instrument()), 1U);
}

TEST(DeterministicMatchingCoreTest, ReservesOutputCapacityForBothSidesOfCloseBarrier) {
  auto engine = DeterministicMatchingCore({instrument()}, 2, 0);

  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000021",
          "0198a001-0000-7000-8000-000000000031",
          CoreSide::kBuy,
          100,
          1'000'000)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000022",
          "0198a001-0000-7000-8000-000000000032",
          CoreSide::kBuy,
          100,
          990'000)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000023",
          "0198a001-0000-7000-8000-000000000033",
          CoreSide::kSell,
          100,
          2'000'000)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(new_order(
          "0198a001-0000-7000-8000-000000000024",
          "0198a001-0000-7000-8000-000000000034",
          CoreSide::kSell,
          100,
          2'010'000)),
      MatchingProcessResult::kApplied);

  EXPECT_EQ(engine.resting_order_count(instrument()), 4U);
  EXPECT_EQ(engine.maximum_output_events(), 5U);
  ASSERT_EQ(
      engine.process(CoreCommand::close(
          context(), uuid("0198a001-0000-7000-8000-000000000025"))),
      MatchingProcessResult::kApplied);
  EXPECT_EQ(engine.events().size(), 4U);
  for (const CoreEvent &event : engine.events()) {
    EXPECT_EQ(event.type, CoreEventType::kOrderExpired);
    EXPECT_EQ(event.cancellation_reason, CoreCancellationReason::kSessionExpired);
  }
}

} // namespace
} // namespace simplematch::matching
