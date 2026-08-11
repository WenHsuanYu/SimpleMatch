#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <algorithm>
#include <array>
#include <string_view>
#include <vector>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::string_view kArtifactIdentity =
    "2026-08-11:7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kSession = "2026-08-11-regular";

CoreUuid uuid(std::string_view value) {
  return CoreUuid::parse(value).value();
}

CoreInstrument instrument() {
  return CoreInstrument::create("XTAI", "2330").value();
}

MatchingCommandContext context() {
  return MatchingCommandContext::create(
             kArtifactIdentity, kSession, "stable-least-loaded-v1", 0)
      .value();
}

CoreCommand new_order(
    std::string_view command_id,
    std::string_view order_id,
    CoreSide side,
    std::int64_t quantity,
    std::int64_t price,
    CoreTimeInForce tif = CoreTimeInForce::kRod) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      instrument(),
      side,
      ShareQuantity(quantity),
      FixedPrice(price),
      CoreOrderType::kLimit,
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

} // namespace
} // namespace simplematch::matching
