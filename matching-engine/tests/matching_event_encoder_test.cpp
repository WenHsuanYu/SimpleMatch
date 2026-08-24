#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/matching_event_encoder.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>
#include <vector>

#include "matching_runtime_v1.pb.h"

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::string_view kArtifactIdentity =
    "2026-08-11:7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kRestedEventId =
    "2c7124e857ca6895c0d58a18341233768def54a5d9f24bbd0f7e7b08e8bb4873";
constexpr std::string_view kTradeEventId =
    "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
constexpr std::string_view kTradeId =
    "033ec379a4a4f1b3b6e5826b4a31731304662b0647e412e59b4abe21afc3241b";
constexpr std::string_view kCancelledEventId =
    "ff5b587caaa9a87bafcb1b293d4e63c27a74442f719b35125fef840c36acae30";
constexpr std::string_view kExpiredEventId =
    "2baddf37ff38053a0d4b69a5359e152e9e83e4c3ac531f7d705d21de286b2a04";

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

std::string fixture_hex(std::string_view name) {
  std::ifstream fixture(std::string(SIMPLEMATCH_TEST_FIXTURE_DIR) + "/" + std::string(name));
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

void expect_record(
    const MatchingEventRecord &record,
    std::string_view fixture_name,
    std::string_view expected_event_id,
    simplematch::matching::runtime::v1::MatchingEventType expected_type) {
  EXPECT_EQ(hex(record.key), expected_event_id);
  EXPECT_EQ(record.partition_id, 0);
  EXPECT_EQ(hex(record.value), fixture_hex(fixture_name));

  simplematch::matching::runtime::v1::MatchingEvent parsed;
  ASSERT_TRUE(parsed.ParseFromString(decode_hex(fixture_hex(fixture_name))));
  EXPECT_EQ(hex(parsed.event_id()), expected_event_id);
  EXPECT_EQ(parsed.event_type(), expected_type);
}

TEST(MatchingEventEncoderTest, PublishesPinnedOrderRestedRecord) {
  DeterministicMatchingCore core({instrument()}, 16, 0);
  ASSERT_EQ(
      core.process(new_order(
          "0198a001-0000-7000-8000-000000000001",
          "0198a001-0000-7000-8000-000000000011",
          CoreSide::kSell)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(core.events().size(), 1U);

  const auto encoded = MatchingEventEncoder().encode(context(), 41, core.events().front());

  ASSERT_TRUE(encoded.has_value());
  expect_record(
      *encoded,
      "cpp-matching-order-rested-v1.hex",
      kRestedEventId,
      simplematch::matching::runtime::v1::MATCHING_EVENT_TYPE_ORDER_RESTED);
}

TEST(MatchingEventEncoderTest, PublishesPinnedCompleteTradeRecord) {
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
  expect_record(
      *encoded,
      "cpp-matching-trade-executed-v1.hex",
      kTradeEventId,
      simplematch::matching::runtime::v1::MATCHING_EVENT_TYPE_TRADE_EXECUTED);
  simplematch::matching::runtime::v1::MatchingEvent parsed;
  ASSERT_TRUE(parsed.ParseFromString(encoded->value));
  EXPECT_EQ(hex(parsed.trade_executed().trade_id()), kTradeId);
  EXPECT_EQ(parsed.trade_executed().match_index(), 0);
  EXPECT_EQ(parsed.trade_executed().aggressor_side(), simplematch::common::v2::SIDE_BUY);
  EXPECT_EQ(parsed.trade_executed().quantity_shares(), 100);
  EXPECT_EQ(parsed.trade_executed().price_units(), 1'000'000);
  EXPECT_EQ(parsed.trade_executed().maker().resulting_state(),
            simplematch::matching::runtime::v1::TRADE_LEG_STATE_FILLED);
  EXPECT_EQ(parsed.trade_executed().taker().resulting_state(),
            simplematch::matching::runtime::v1::TRADE_LEG_STATE_FILLED);
}

TEST(MatchingEventEncoderTest, PublishesPinnedOrderCancelledRecord) {
  DeterministicMatchingCore core({instrument()}, 16, 0);
  const auto resting = new_order(
      "0198a001-0000-7000-8000-000000000001",
      "0198a001-0000-7000-8000-000000000011",
      CoreSide::kSell);
  ASSERT_EQ(core.process(resting), MatchingProcessResult::kApplied);
  ASSERT_EQ(
      core.process(CoreCommand::cancel(
          context(),
          uuid("0198a001-0000-7000-8000-000000000003"),
          resting.new_order().order_id,
          resting.new_order().account_id,
          instrument(),
          CoreSide::kSell)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(core.events().size(), 1U);

  const auto encoded = MatchingEventEncoder().encode(context(), 43, core.events().front());

  ASSERT_TRUE(encoded.has_value());
  expect_record(
      *encoded,
      "cpp-matching-order-cancelled-v1.hex",
      kCancelledEventId,
      simplematch::matching::runtime::v1::MATCHING_EVENT_TYPE_ORDER_CANCELLED);
}

TEST(MatchingEventEncoderTest, PublishesPinnedOrderExpiredRecord) {
  DeterministicMatchingCore core({instrument()}, 16, 0);
  ASSERT_EQ(
      core.process(new_order(
          "0198a001-0000-7000-8000-000000000004",
          "0198a001-0000-7000-8000-000000000014",
          CoreSide::kBuy)),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      core.process(CoreCommand::close(
          context(), uuid("0198a001-0000-7000-8000-000000000005"))),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(core.events().size(), 1U);

  const auto encoded = MatchingEventEncoder().encode(context(), 45, core.events().front());

  ASSERT_TRUE(encoded.has_value());
  expect_record(
      *encoded,
      "cpp-matching-order-expired-v1.hex",
      kExpiredEventId,
      simplematch::matching::runtime::v1::MATCHING_EVENT_TYPE_ORDER_EXPIRED);
}

std::vector<MatchingEventRecord> publish_two_command_sequence() {
  DeterministicMatchingCore core({instrument()}, 16, 0);
  MatchingEventEncoder encoder;
  std::vector<MatchingEventRecord> records;
  const auto publish = [&](const CoreCommand &command, std::int64_t input_offset) {
    EXPECT_EQ(core.process(command), MatchingProcessResult::kApplied);
    for (const CoreEvent &event : core.events()) {
      const auto encoded = encoder.encode(command.context, input_offset, event);
      EXPECT_TRUE(encoded.has_value());
      if (encoded.has_value()) {
        records.push_back(*encoded);
      }
    }
  };
  publish(
      new_order(
          "0198a001-0000-7000-8000-000000000001",
          "0198a001-0000-7000-8000-000000000011",
          CoreSide::kSell),
      41);
  publish(
      new_order(
          "0198a001-0000-7000-8000-000000000002",
          "0198a001-0000-7000-8000-000000000012",
          CoreSide::kBuy),
      42);
  return records;
}

TEST(MatchingEventEncoderTest, ReplayProducesTheSameKafkaRecordsByteForByte) {
  const auto first = publish_two_command_sequence();
  const auto replay = publish_two_command_sequence();

  ASSERT_EQ(first.size(), replay.size());
  for (std::size_t index = 0; index < first.size(); ++index) {
    EXPECT_EQ(first[index].key, replay[index].key);
    EXPECT_EQ(first[index].partition_id, replay[index].partition_id);
    EXPECT_EQ(first[index].source_input_offset, replay[index].source_input_offset);
    EXPECT_EQ(first[index].output_index, replay[index].output_index);
    EXPECT_EQ(first[index].value, replay[index].value);
  }
}

} // namespace
} // namespace simplematch::matching
