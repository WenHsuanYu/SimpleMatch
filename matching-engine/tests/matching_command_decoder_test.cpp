#include "simplematch/matching/ingress/matching_command_decoder.hpp"

#include <string>

#include "matching_runtime_v1.pb.h"

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr char kArtifactSha256[] =
    "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";

simplematch::matching::runtime::v1::MatchingCommand valid_new_order() {
  simplematch::matching::runtime::v1::MatchingCommand command;
  auto *header = command.mutable_header();
  header->set_schema_version(1);
  header->set_command_id("0198a001-0000-7000-8000-000000000001");
  header->set_trading_session_id("2026-08-11-regular");
  header->set_partition_id(3);
  header->mutable_artifact_identity()->set_trading_day("2026-08-11");
  header->mutable_artifact_identity()->set_content_sha256(kArtifactSha256);
  header->set_routing_algorithm_version("stable-least-loaded-v1");

  auto *order = command.mutable_new_order();
  order->set_order_id("0198a001-0000-7000-8000-000000000011");
  order->set_account_id("0198a001-0000-7000-8000-0000000000aa");
  order->mutable_instrument()->set_venue_mic("XTAI");
  order->mutable_instrument()->set_symbol("2330");
  order->set_side(simplematch::common::v2::SIDE_BUY);
  order->set_quantity_shares(100);
  order->set_limit_price_units(1'000'000);
  order->set_order_type(simplematch::common::v2::ORDER_TYPE_LIMIT);
  order->set_time_in_force(simplematch::common::v2::TIME_IN_FORCE_ROD);
  return command;
}

TEST(MatchingCommandDecoderTest, DecodesOnlyAnExplicitlyAssignedFinalCommand) {
  const auto wire = valid_new_order();

  const auto decoded = MatchingCommandDecoder().decode(wire.SerializeAsString());

  ASSERT_TRUE(decoded.accepted());
  EXPECT_EQ(decoded.command.type, CoreCommandType::kNewOrder);
  EXPECT_EQ(decoded.command.context.partition_id, 3);
  EXPECT_EQ(std::string(decoded.command.context.artifact_identity.view()),
            std::string("2026-08-11:") + kArtifactSha256);
  EXPECT_EQ(decoded.command.new_order().quantity.value(), 100);
}

TEST(MatchingCommandDecoderTest, RejectsNonCanonicalArtifactHashBeforeTheCore) {
  auto wire = valid_new_order();
  wire.mutable_header()->mutable_artifact_identity()->set_content_sha256(
      "7CD06C51691BCDE248E606ED1ADFADDC4BD10ECE582A6803FD2F04155A032943");

  const auto decoded = MatchingCommandDecoder().decode(wire.SerializeAsString());

  EXPECT_EQ(decoded.error, MatchingCommandDecodeError::kInvalidArtifactIdentity);
}

TEST(MatchingCommandDecoderTest, RejectsAHeaderWithoutAnOrderedCommand) {
  auto wire = valid_new_order();
  wire.clear_command();

  const auto decoded = MatchingCommandDecoder().decode(wire.SerializeAsString());

  EXPECT_EQ(decoded.error, MatchingCommandDecodeError::kInvalidCommand);
}

} // namespace
} // namespace simplematch::matching
