#include "simplematch/matching/runtime/matching_event_encoder.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <limits>
#include <string>
#include <string_view>

#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <openssl/sha.h>

#include "matching_runtime_v1.pb.h"

namespace simplematch::matching {
namespace {

constexpr std::size_t kSha256HexLength = 64;
constexpr std::size_t kHashInputCapacity = 256;
constexpr std::int32_t kIdentityVersion = 1;
constexpr char kHex[] = "0123456789abcdef";

struct ArtifactParts {
  std::string_view trading_day;
  std::string_view content_sha256;
};

class HashInput {
public:
  void text(std::string_view value) {
    append_length(value.size());
    append(value);
  }

  void integer(std::int32_t value) {
    const auto unsigned_value = static_cast<std::uint32_t>(value);
    for (int byte = 3; byte >= 0; --byte) {
      append_byte(static_cast<std::uint8_t>(unsigned_value >> (byte * 8)));
    }
  }

  void uuid(const CoreUuid &value) {
    for (const auto byte : value.bytes) {
      append_byte(byte);
    }
  }

  [[nodiscard]] std::array<unsigned char, SHA256_DIGEST_LENGTH> digest() const {
    std::array<unsigned char, SHA256_DIGEST_LENGTH> result{};
    SHA256(bytes_.data(), size_, result.data());
    return result;
  }

private:
  void append_length(std::size_t value) {
    if (value > std::numeric_limits<std::uint32_t>::max()) {
      throw std::invalid_argument("deterministic identity input is too long");
    }
    integer(static_cast<std::int32_t>(value));
  }

  void append(std::string_view value) {
    if (size_ + value.size() > bytes_.size()) {
      throw std::invalid_argument("deterministic identity input exceeds fixed capacity");
    }
    std::memcpy(bytes_.data() + size_, value.data(), value.size());
    size_ += value.size();
  }

  void append_byte(std::uint8_t value) {
    if (size_ == bytes_.size()) {
      throw std::invalid_argument("deterministic identity input exceeds fixed capacity");
    }
    bytes_[size_++] = value;
  }

  std::array<unsigned char, kHashInputCapacity> bytes_{};
  std::size_t size_{};
};

std::array<unsigned char, SHA256_DIGEST_LENGTH> event_id(
    const MatchingCommandContext &context, const CoreUuid &command_id, std::int32_t output_index) {
  HashInput input;
  input.text("simplematch.event-id.v1");
  input.integer(kIdentityVersion);
  input.text(context.trading_session_id.view());
  input.integer(context.partition_id);
  input.uuid(command_id);
  input.integer(output_index);
  return input.digest();
}

std::array<unsigned char, SHA256_DIGEST_LENGTH> trade_id(
    const MatchingCommandContext &context, const CoreUuid &command_id, std::int32_t match_index) {
  HashInput input;
  input.text("simplematch.trade-id.v1");
  input.integer(kIdentityVersion);
  input.text(context.trading_session_id.view());
  input.integer(context.partition_id);
  input.uuid(command_id);
  input.integer(match_index);
  return input.digest();
}

template <std::size_t Size>
std::string raw_bytes(const std::array<unsigned char, Size> &value) {
  return {reinterpret_cast<const char *>(value.data()), value.size()};
}

std::optional<ArtifactParts> artifact_parts(std::string_view identity) {
  const auto separator = identity.find(':');
  if (separator == std::string_view::npos || separator == 0 ||
      separator + 1 + kSha256HexLength != identity.size()) {
    return std::nullopt;
  }
  const std::string_view sha256 = identity.substr(separator + 1);
  const bool canonical = std::all_of(
      sha256.begin(), sha256.end(), [](unsigned char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
      });
  if (!canonical) {
    return std::nullopt;
  }
  return ArtifactParts{identity.substr(0, separator), sha256};
}

std::string uuid_text(const CoreUuid &value) {
  std::string encoded;
  encoded.reserve(36);
  for (std::size_t index = 0; index < value.bytes.size(); ++index) {
    if (index == 4 || index == 6 || index == 8 || index == 10) {
      encoded.push_back('-');
    }
    encoded.push_back(kHex[value.bytes[index] >> 4]);
    encoded.push_back(kHex[value.bytes[index] & 0x0F]);
  }
  return encoded;
}

std::string instrument_venue(const CoreInstrument &instrument) {
  return {instrument.venue.data(), instrument.venue.size()};
}

std::string instrument_symbol(const CoreInstrument &instrument) {
  return {instrument.symbol.data(), instrument.symbol_length};
}

simplematch::common::v2::Side side(CoreSide value) {
  return value == CoreSide::kBuy ? simplematch::common::v2::SIDE_BUY
                                 : simplematch::common::v2::SIDE_SELL;
}

simplematch::matching::runtime::v1::TradeLegState trade_leg_state(std::int64_t leaves_quantity) {
  return leaves_quantity == 0
             ? simplematch::matching::runtime::v1::TRADE_LEG_STATE_FILLED
             : simplematch::matching::runtime::v1::TRADE_LEG_STATE_PARTIALLY_FILLED;
}

std::optional<simplematch::matching::runtime::v1::MatchingEventType> event_type(
    CoreEventType value) {
  using WireEventType = simplematch::matching::runtime::v1::MatchingEventType;
  switch (value) {
    case CoreEventType::kOrderRested:
      return WireEventType::MATCHING_EVENT_TYPE_ORDER_RESTED;
    case CoreEventType::kTradeExecuted:
      return WireEventType::MATCHING_EVENT_TYPE_TRADE_EXECUTED;
    case CoreEventType::kOrderCancelled:
      return WireEventType::MATCHING_EVENT_TYPE_ORDER_CANCELLED;
    case CoreEventType::kOrderExpired:
      return WireEventType::MATCHING_EVENT_TYPE_ORDER_EXPIRED;
  }
  return std::nullopt;
}

std::optional<simplematch::matching::runtime::v1::CancellationReason> cancellation_reason(
    CoreCancellationReason value) {
  using WireReason = simplematch::matching::runtime::v1::CancellationReason;
  switch (value) {
    case CoreCancellationReason::kUserRequest:
      return WireReason::CANCELLATION_REASON_USER_REQUEST;
    case CoreCancellationReason::kIocRemainder:
      return WireReason::CANCELLATION_REASON_IOC_REMAINDER;
    case CoreCancellationReason::kFokNotFilled:
      return WireReason::CANCELLATION_REASON_FOK_NOT_FILLED;
    case CoreCancellationReason::kSessionExpired:
      return WireReason::CANCELLATION_REASON_SESSION_EXPIRED;
    case CoreCancellationReason::kNone:
      return std::nullopt;
  }
  return std::nullopt;
}

void set_instrument(
    const CoreInstrument &instrument,
    simplematch::common::v2::VenueInstrument *wire) {
  wire->set_venue_mic(instrument_venue(instrument));
  wire->set_symbol(instrument_symbol(instrument));
}

bool valid_trade(const CoreEvent &event) {
  return event.match_index >= 0 && event.quantity.value() > 0 && event.price.value() > 0 &&
         event.maker_order_id != event.taker_order_id && event.maker_side != event.taker_side &&
         event.maker_cumulative_quantity.value() >= event.quantity.value() &&
         event.taker_cumulative_quantity.value() >= event.quantity.value() &&
         event.maker_leaves_quantity.value() >= 0 && event.taker_leaves_quantity.value() >= 0 &&
         event.maker_average_price.value() > 0 && event.taker_average_price.value() > 0;
}

bool serialize_deterministically(
    const simplematch::matching::runtime::v1::MatchingEvent &wire,
    std::string *value) {
  google::protobuf::io::StringOutputStream stream(value);
  google::protobuf::io::CodedOutputStream output(&stream);
  output.SetSerializationDeterministic(true);
  return wire.SerializeToCodedStream(&output) && !output.HadError();
}

} // namespace

std::optional<MatchingEventRecord> MatchingEventEncoder::encode(
    const MatchingCommandContext &context,
    std::int64_t source_input_offset,
    const CoreEvent &event) const {
  if (source_input_offset < 0 || event.output_index < 0 || context.partition_id < 0 ||
      context.trading_session_id.empty()) {
    return std::nullopt;
  }
  const auto artifact = artifact_parts(context.artifact_identity.view());
  const auto decoded_event_type = event_type(event.type);
  if (!artifact.has_value() || !decoded_event_type.has_value()) {
    return std::nullopt;
  }

  const auto encoded_event_id = event_id(context, event.source_command_id, event.output_index);
  const std::string event_id_bytes = raw_bytes(encoded_event_id);

  simplematch::matching::runtime::v1::MatchingEvent wire;
  wire.set_schema_version(1);
  wire.set_identity_version(kIdentityVersion);
  wire.set_event_id(event_id_bytes);
  wire.set_trading_session_id(std::string(context.trading_session_id.view()));
  wire.set_partition_id(context.partition_id);
  wire.set_source_command_id(uuid_text(event.source_command_id));
  wire.set_source_input_offset(source_input_offset);
  wire.set_output_index(event.output_index);
  wire.mutable_artifact_identity()->set_trading_day(artifact->trading_day);
  wire.mutable_artifact_identity()->set_content_sha256(artifact->content_sha256);
  wire.set_routing_algorithm_version(std::string(context.routing_algorithm_version.view()));
  wire.set_event_type(*decoded_event_type);

  switch (event.type) {
    case CoreEventType::kOrderRested: {
      if (event.leaves_quantity.value() <= 0 || event.price.value() <= 0) {
        return std::nullopt;
      }
      auto *rested = wire.mutable_order_rested();
      rested->set_order_id(uuid_text(event.order_id));
      rested->set_account_id(uuid_text(event.account_id));
      set_instrument(event.instrument, rested->mutable_instrument());
      rested->set_side(side(event.side));
      rested->set_leaves_quantity_shares(event.leaves_quantity.value());
      rested->set_resting_price_units(event.price.value());
      break;
    }
    case CoreEventType::kTradeExecuted: {
      if (!valid_trade(event)) {
        return std::nullopt;
      }
      const auto encoded_trade_id = trade_id(context, event.source_command_id, event.match_index);
      auto *trade = wire.mutable_trade_executed();
      trade->set_trade_id(raw_bytes(encoded_trade_id));
      set_instrument(event.instrument, trade->mutable_instrument());
      trade->set_match_index(event.match_index);
      trade->set_aggressor_side(side(event.taker_side));
      trade->set_quantity_shares(event.quantity.value());
      trade->set_price_units(event.price.value());
      auto *maker = trade->mutable_maker();
      maker->set_order_id(uuid_text(event.maker_order_id));
      maker->set_account_id(uuid_text(event.maker_account_id));
      maker->set_side(side(event.maker_side));
      maker->set_cumulative_quantity_shares(event.maker_cumulative_quantity.value());
      maker->set_leaves_quantity_shares(event.maker_leaves_quantity.value());
      maker->set_average_price_units(event.maker_average_price.value());
      maker->set_resulting_state(trade_leg_state(event.maker_leaves_quantity.value()));
      auto *taker = trade->mutable_taker();
      taker->set_order_id(uuid_text(event.taker_order_id));
      taker->set_account_id(uuid_text(event.taker_account_id));
      taker->set_side(side(event.taker_side));
      taker->set_cumulative_quantity_shares(event.taker_cumulative_quantity.value());
      taker->set_leaves_quantity_shares(event.taker_leaves_quantity.value());
      taker->set_average_price_units(event.taker_average_price.value());
      taker->set_resulting_state(trade_leg_state(event.taker_leaves_quantity.value()));
      break;
    }
    case CoreEventType::kOrderCancelled:
    case CoreEventType::kOrderExpired: {
      const auto reason = cancellation_reason(event.cancellation_reason);
      if (!reason.has_value() || event.leaves_quantity.value() <= 0) {
        return std::nullopt;
      }
      auto *terminal = event.type == CoreEventType::kOrderCancelled
                           ? wire.mutable_order_cancelled()
                           : wire.mutable_order_expired();
      terminal->set_order_id(uuid_text(event.order_id));
      terminal->set_account_id(uuid_text(event.account_id));
      set_instrument(event.instrument, terminal->mutable_instrument());
      terminal->set_side(side(event.side));
      terminal->set_leaves_quantity_shares(event.leaves_quantity.value());
      terminal->set_reason(*reason);
      break;
    }
  }

  std::string value;
  if (!serialize_deterministically(wire, &value)) {
    return std::nullopt;
  }
  return MatchingEventRecord{
      event_id_bytes,
      context.partition_id,
      source_input_offset,
      event.output_index,
      std::move(value)};
}

} // namespace simplematch::matching
