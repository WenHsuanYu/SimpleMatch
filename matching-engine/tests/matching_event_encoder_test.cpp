#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/matching_event_encoder.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

#include "matching_runtime_v1.pb.h"

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::string_view kArtifactIdentity =
    "2026-08-11:7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kExpectedEventId =
    "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
constexpr std::string_view kExpectedTradeId =
    "033ec379a4a4f1b3b6e5826b4a31731304662b0647e412e59b4abe21afc3241b";

int hex_value(char character) {
  if (character >= '0' && character <= '9') {
    return character - '0';
  }
  if (character >= 'a' && character <= 'f') {
    return character - 'a' + 10;
  }
  return -1;
}

std::string hex(std::string_view value) {
  constexpr char digits[] = "0123456789abcdef";
  std::string encoded;
  encoded.reserve(value.size() * 2);
  for (const unsigned char character : value) {
    encoded.push_back(digits[character >> 4]);
    encoded.push_back(digits[character & 0x0F]);
  }
  return encoded;
}

std::string fixture_hex() {
  std::ifstream fixture(
      std::string(SIMPLEMATCH_TEST_FIXTURE_DIR) + "/cpp-matching-event-v1.hex");
  std::string encoded{std::istreambuf_iterator<char>(fixture), {}};
  encoded.erase(
      std::remove_if(encoded.begin(), encoded.end(), [](unsigned char character) {
        return std::isspace(character) != 0;
      }),
      encoded.end());
  return encoded;
}

std::string decode_hex(std::string_view encoded) {
  std::string decoded;
  for (std::size_t index = 0; index < encoded.size(); ++index) {
    if (std::isspace(static_cast<unsigned char>(encoded[index]))) {
      continue;
    }
    const int high = hex_value(encoded[index]);
    if (high < 0 || ++index >= encoded.size()) {
      return {};
    }
    const int low = hex_value(encoded[index]);
    if (low < 0) {
      return {};
    }
    decoded.push_back(static_cast<char>((high << 4) | low));
  }
  return decoded;
}

CoreUuid uuid(std::string_view value) {
  return CoreUuid::parse(value).value();
}

CoreInstrument instrument() {
  return CoreInstrument::create("XTAI", "2330").value();
}

MatchingCommandContext context() {
  return MatchingCommandContext::create(
             kArtifactIdentity, "2026-08-11-regular", "stable-least-loaded-v1", 0)
      .value();
}

CoreCommand new_order(
    std::string_view command_id,
    std::string_view order_id,
    CoreSide side) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      instrument(),
      side,
      ShareQuantity(100),
      FixedPrice(1'000'000),
      CoreOrderType::kLimit,
      CoreTimeInForce::kRod);
}

TEST(MatchingEventEncoderTest, ProducesPinnedGoldenBytesForACompleteTwoLegTrade) {
  DeterministicMatchingCore core({instrument()}, 16, 0);
  ASSERT_EQ(
      core.process(new_order(
          "0198a001-0000-7000-8000-000000000001",
          "0198a001-0000-7000-8000-000000000011",
          CoreSide::kSell)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      core.process(new_order(
          "0198a001-0000-7000-8000-000000000002",
          "0198a001-0000-7000-8000-000000000012",
          CoreSide::kBuy)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(core.events().size(), 1U);

  const auto encoded = MatchingEventEncoder().encode(context(), 42, core.events().front());

  ASSERT_TRUE(encoded.has_value());
  EXPECT_EQ(hex(encoded->key), kExpectedEventId);
  EXPECT_EQ(encoded->partition_id, 0);
  EXPECT_EQ(encoded->source_input_offset, 42);
  EXPECT_EQ(hex(encoded->value), fixture_hex());

  simplematch::matching::runtime::v1::MatchingEvent parsed;
  ASSERT_TRUE(parsed.ParseFromString(decode_hex(fixture_hex())));
  EXPECT_EQ(hex(parsed.event_id()), kExpectedEventId);
  EXPECT_EQ(parsed.event_type(),
            simplematch::matching::runtime::v1::MATCHING_EVENT_TYPE_TRADE_EXECUTED);
  EXPECT_EQ(hex(parsed.trade_executed().trade_id()), kExpectedTradeId);
  EXPECT_EQ(parsed.trade_executed().match_index(), 0);
  EXPECT_EQ(parsed.trade_executed().aggressor_side(), simplematch::common::v2::SIDE_BUY);
  EXPECT_EQ(parsed.trade_executed().quantity_shares(), 100);
  EXPECT_EQ(parsed.trade_executed().price_units(), 1'000'000);
  EXPECT_EQ(parsed.trade_executed().maker().resulting_state(),
            simplematch::matching::runtime::v1::TRADE_LEG_STATE_FILLED);
  EXPECT_EQ(parsed.trade_executed().taker().resulting_state(),
            simplematch::matching::runtime::v1::TRADE_LEG_STATE_FILLED);
}

} // namespace
} // namespace simplematch::matching
