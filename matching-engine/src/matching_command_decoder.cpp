#include "simplematch/matching/ingress/matching_command_decoder.hpp"

#include <algorithm>
#include <cctype>
#include <limits>

#include "matching_runtime_v1.pb.h"

namespace simplematch::matching {
namespace {

MatchingCommandDecodeResult failure(MatchingCommandDecodeError error) {
  return {error, CoreCommand::close(MatchingCommandContext{}, CoreUuid{}), {}};
}

std::optional<CoreSide> side(simplematch::common::v2::Side value) {
  switch (value) {
    case simplematch::common::v2::SIDE_BUY:
      return CoreSide::kBuy;
    case simplematch::common::v2::SIDE_SELL:
      return CoreSide::kSell;
    default:
      return std::nullopt;
  }
}

std::optional<CoreOrderType> order_type(simplematch::common::v2::OrderType value) {
  switch (value) {
    case simplematch::common::v2::ORDER_TYPE_LIMIT:
      return CoreOrderType::kLimit;
    case simplematch::common::v2::ORDER_TYPE_MARKET:
      return CoreOrderType::kMarket;
    default:
      return std::nullopt;
  }
}

std::optional<CoreTimeInForce> time_in_force(simplematch::common::v2::TimeInForce value) {
  switch (value) {
    case simplematch::common::v2::TIME_IN_FORCE_ROD:
      return CoreTimeInForce::kRod;
    case simplematch::common::v2::TIME_IN_FORCE_IOC:
      return CoreTimeInForce::kIoc;
    case simplematch::common::v2::TIME_IN_FORCE_FOK:
      return CoreTimeInForce::kFok;
    default:
      return std::nullopt;
  }
}

bool canonical_sha256(std::string_view value) {
  return value.size() == 64 &&
         std::all_of(value.begin(), value.end(), [](unsigned char character) {
           return (character >= '0' && character <= '9') ||
                  (character >= 'a' && character <= 'f');
         });
}

bool canonical_image_digest(std::string_view value) {
  return value.starts_with("sha256:") && canonical_sha256(value.substr(7));
}

} // namespace

MatchingCommandDecodeResult MatchingCommandDecoder::decode(std::string_view payload) const {
  if (payload.empty() || payload.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
    return failure(MatchingCommandDecodeError::kInvalidPayload);
  }
  simplematch::matching::runtime::v1::MatchingCommand wire;
  if (!wire.ParseFromArray(payload.data(), static_cast<int>(payload.size())) || !wire.has_header()) {
    return failure(MatchingCommandDecodeError::kInvalidPayload);
  }
  const auto &header = wire.header();
  if (header.schema_version() != 1 || header.command_id().empty() ||
      header.trading_session_id().empty() || header.partition_id() < 0 ||
      !header.has_artifact_identity() || header.routing_algorithm_version().empty()) {
    return failure(MatchingCommandDecodeError::kInvalidHeader);
  }
  const auto command_id = CoreUuid::parse(header.command_id());
  const auto &artifact = header.artifact_identity();
  if (!command_id.has_value() || artifact.trading_day().empty() ||
      !canonical_sha256(artifact.content_sha256())) {
    return failure(MatchingCommandDecodeError::kInvalidArtifactIdentity);
  }
  const auto context =
      MatchingCommandContext::create(
          artifact.trading_day() + ":" + artifact.content_sha256(),
          header.trading_session_id(),
          header.routing_algorithm_version(),
          header.partition_id());
  if (!context.has_value()) {
    return failure(MatchingCommandDecodeError::kInvalidHeader);
  }
  switch (wire.command_case()) {
    case simplematch::matching::runtime::v1::MatchingCommand::kNewOrder: {
      const auto &order = wire.new_order();
      const auto order_id = CoreUuid::parse(order.order_id());
      const auto account_id = CoreUuid::parse(order.account_id());
      const auto decoded_instrument =
          CoreInstrument::create(order.instrument().venue_mic(), order.instrument().symbol());
      const auto decoded_side = side(order.side());
      const auto decoded_order_type = order_type(order.order_type());
      const auto decoded_tif = time_in_force(order.time_in_force());
      if (!order_id.has_value() || !account_id.has_value() || !decoded_instrument.has_value() ||
          !decoded_side.has_value() || !decoded_order_type.has_value() || !decoded_tif.has_value() ||
          order.quantity_shares() <= 0 ||
          (*decoded_order_type == CoreOrderType::kLimit && order.limit_price_units() <= 0)) {
        return failure(MatchingCommandDecodeError::kInvalidCommand);
      }
      return {MatchingCommandDecodeError::kNone,
              CoreCommand::new_order(
                  *context,
                  *command_id,
                  *order_id,
                  *account_id,
                  *decoded_instrument,
                  *decoded_side,
                  ShareQuantity(order.quantity_shares()),
                  FixedPrice(order.limit_price_units()),
                  *decoded_order_type,
                  *decoded_tif),
              {header.schema_version(), 0, 0, {}}};
    }
    case simplematch::matching::runtime::v1::MatchingCommand::kCancelOrder: {
      const auto &cancel = wire.cancel_order();
      const auto order_id = CoreUuid::parse(cancel.order_id());
      const auto account_id = CoreUuid::parse(cancel.account_id());
      const auto decoded_instrument =
          CoreInstrument::create(cancel.instrument().venue_mic(), cancel.instrument().symbol());
      const auto decoded_side = side(cancel.side());
      if (!order_id.has_value() || !account_id.has_value() || !decoded_instrument.has_value() ||
          !decoded_side.has_value()) {
        return failure(MatchingCommandDecodeError::kInvalidCommand);
      }
      return {MatchingCommandDecodeError::kNone,
              CoreCommand::cancel(
                  *context,
                  *command_id,
                  *order_id,
                  *account_id,
                  *decoded_instrument,
                  *decoded_side),
              {header.schema_version(), 0, 0, {}}};
    }
    case simplematch::matching::runtime::v1::MatchingCommand::kOpenBarrier: {
      const auto &barrier = wire.open_barrier();
      if (barrier.expected_partition_count() != 15 ||
          barrier.event_schema_version() <= 0 ||
          barrier.event_identity_version() <= 0 ||
          !canonical_image_digest(barrier.matching_image_digest())) {
        return failure(MatchingCommandDecodeError::kInvalidCommand);
      }
      return {MatchingCommandDecodeError::kNone,
              CoreCommand::open(
                  *context, *command_id, barrier.expected_partition_count()),
              {header.schema_version(),
               barrier.event_schema_version(),
               barrier.event_identity_version(),
               barrier.matching_image_digest()}};
    }
    case simplematch::matching::runtime::v1::MatchingCommand::kCloseBarrier:
      return {MatchingCommandDecodeError::kNone,
              CoreCommand::close(*context, *command_id),
              {header.schema_version(), 0, 0, {}}};
    case simplematch::matching::runtime::v1::MatchingCommand::COMMAND_NOT_SET:
      return failure(MatchingCommandDecodeError::kInvalidCommand);
  }
  return failure(MatchingCommandDecodeError::kInvalidCommand);
}

} // namespace simplematch::matching
