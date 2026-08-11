#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string_view>
#include <utility>

#include <openssl/sha.h>

namespace simplematch::matching {
namespace {

constexpr std::size_t kMaximumBooks = 150;
constexpr std::size_t kHashInputCapacity = 256;
constexpr char kHex[] = "0123456789abcdef";

int hex_value(char character) {
  if (character >= '0' && character <= '9') {
    return character - '0';
  }
  if (character >= 'a' && character <= 'f') {
    return character - 'a' + 10;
  }
  if (character >= 'A' && character <= 'F') {
    return character - 'A' + 10;
  }
  return -1;
}

char uppercase(char value) {
  return static_cast<char>(std::toupper(static_cast<unsigned char>(value)));
}

bool valid_symbol(std::string_view value) {
  return !value.empty() && value.size() <= 12 &&
         std::all_of(value.begin(), value.end(), [](unsigned char character) {
           return std::isalnum(character) != 0;
         });
}

bool same_instrument(const CoreInstrument &left, const CoreInstrument &right) {
  return left == right;
}

bool valid_context(const MatchingCommandContext &context, std::int32_t owned_partition) {
  return context.partition_id == owned_partition && !context.artifact_identity.empty() &&
         !context.trading_session_id.empty() && !context.routing_algorithm_version.empty();
}

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

  [[nodiscard]] std::array<char, 65> digest_hex() const {
    std::array<unsigned char, SHA256_DIGEST_LENGTH> digest{};
    SHA256(bytes_.data(), size_, digest.data());
    std::array<char, 65> encoded{};
    for (std::size_t index = 0; index < digest.size(); ++index) {
      encoded[index * 2] = kHex[digest[index] >> 4];
      encoded[index * 2 + 1] = kHex[digest[index] & 0x0F];
    }
    return encoded;
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

std::array<char, 65> event_id(
    const MatchingCommandContext &context, const CoreUuid &command_id, std::int32_t output_index) {
  HashInput input;
  input.text("simplematch.event-id.v1");
  input.integer(1);
  input.text(context.trading_session_id.view());
  input.integer(context.partition_id);
  input.uuid(command_id);
  input.integer(output_index);
  return input.digest_hex();
}

std::array<char, 65> trade_id(
    const MatchingCommandContext &context, const CoreUuid &command_id, std::int32_t match_index) {
  HashInput input;
  input.text("simplematch.trade-id.v1");
  input.integer(1);
  input.text(context.trading_session_id.view());
  input.integer(context.partition_id);
  input.uuid(command_id);
  input.integer(match_index);
  return input.digest_hex();
}

} // namespace

struct DeterministicMatchingCore::RestingOrder {
  CoreUuid order_id;
  CoreUuid account_id;
  CoreSide side;
  ShareQuantity original_quantity;
  ShareQuantity remaining;
  FixedPrice price;
  std::uint64_t arrival_sequence;
};

struct DeterministicMatchingCore::Book {
  explicit Book(CoreInstrument assigned_instrument, std::size_t maximum_orders)
      : instrument(assigned_instrument) {
    buys.reserve(maximum_orders);
    sells.reserve(maximum_orders);
  }

  CoreInstrument instrument;
  std::vector<RestingOrder> buys;
  std::vector<RestingOrder> sells;
};

DeterministicMatchingCore::~DeterministicMatchingCore() = default;

std::optional<CoreUuid> CoreUuid::parse(std::string_view text) {
  if (text.size() != 36 || text[8] != '-' || text[13] != '-' || text[18] != '-' ||
      text[23] != '-') {
    return std::nullopt;
  }
  CoreUuid parsed;
  std::size_t target = 0;
  for (std::size_t index = 0; index < text.size(); ++index) {
    if (text[index] == '-') {
      continue;
    }
    if (index + 1 >= text.size()) {
      return std::nullopt;
    }
    const int high = hex_value(text[index]);
    ++index;
    const int low = hex_value(text[index]);
    if (high < 0 || low < 0 || target >= parsed.bytes.size()) {
      return std::nullopt;
    }
    parsed.bytes[target++] = static_cast<std::uint8_t>((high << 4) | low);
  }
  return target == parsed.bytes.size() ? std::optional(parsed) : std::nullopt;
}

ShareQuantity::ShareQuantity(std::int64_t value) : value_(value) {}

std::int64_t ShareQuantity::value() const {
  return value_;
}

FixedPrice::FixedPrice(std::int64_t value) : value_(value) {}

std::int64_t FixedPrice::value() const {
  return value_;
}

std::optional<CoreInstrument> CoreInstrument::create(
    std::string_view venue_value, std::string_view symbol_value) {
  if (venue_value.size() != 4 || !valid_symbol(symbol_value)) {
    return std::nullopt;
  }
  CoreInstrument instrument;
  for (std::size_t index = 0; index < venue_value.size(); ++index) {
    instrument.venue[index] = uppercase(venue_value[index]);
  }
  if (std::string_view(instrument.venue.data(), instrument.venue.size()) != "XTAI" &&
      std::string_view(instrument.venue.data(), instrument.venue.size()) != "ROCO") {
    return std::nullopt;
  }
  instrument.symbol_length = static_cast<std::uint8_t>(symbol_value.size());
  for (std::size_t index = 0; index < symbol_value.size(); ++index) {
    instrument.symbol[index] = uppercase(symbol_value[index]);
  }
  return instrument;
}

std::optional<MatchingCommandContext> MatchingCommandContext::create(
    std::string_view artifact_identity_value,
    std::string_view trading_session_value,
    std::string_view routing_algorithm_value,
    std::int32_t partition_id_value) {
  const auto artifact = FixedText<96>::create(artifact_identity_value);
  const auto session = FixedText<64>::create(trading_session_value);
  const auto algorithm = FixedText<64>::create(routing_algorithm_value);
  if (!artifact.has_value() || !session.has_value() || !algorithm.has_value() ||
      partition_id_value < 0) {
    return std::nullopt;
  }
  return MatchingCommandContext{*artifact, *session, *algorithm, partition_id_value};
}

CoreCommand CoreCommand::new_order(
    MatchingCommandContext context,
    CoreUuid command_id,
    CoreUuid order_id,
    CoreUuid account_id,
    CoreInstrument instrument,
    CoreSide side,
    ShareQuantity quantity,
    FixedPrice limit_price,
    CoreOrderType order_type,
    CoreTimeInForce time_in_force) {
  return {context,
          CoreCommandType::kNewOrder,
          command_id,
          0,
          {command_id,
           order_id,
           account_id,
           instrument,
           side,
           quantity,
           limit_price,
           order_type,
           time_in_force},
          {}};
}

CoreCommand CoreCommand::open(
    MatchingCommandContext context, CoreUuid command_id, std::int32_t expected_partition_count) {
  return {context, CoreCommandType::kOpenBarrier, command_id, expected_partition_count, {}, {}};
}

CoreCommand CoreCommand::cancel(
    MatchingCommandContext context,
    CoreUuid command_id,
    CoreUuid order_id,
    CoreUuid account_id,
    CoreInstrument instrument,
    CoreSide side) {
  return {context,
          CoreCommandType::kCancelOrder,
          command_id,
          0,
          {},
          {command_id, order_id, account_id, instrument, side}};
}

CoreCommand CoreCommand::close(MatchingCommandContext context, CoreUuid command_id) {
  return {context, CoreCommandType::kCloseBarrier, command_id, 0, {}, {}};
}

const CoreNewOrder &CoreCommand::new_order() const {
  if (type != CoreCommandType::kNewOrder) {
    throw std::logic_error("command is not a new order");
  }
  return new_order_payload;
}

const CoreCancelOrder &CoreCommand::cancel_order() const {
  if (type != CoreCommandType::kCancelOrder) {
    throw std::logic_error("command is not a cancel order");
  }
  return cancel_order_payload;
}

DeterministicMatchingCore::DeterministicMatchingCore(
    std::vector<CoreInstrument> assigned_instruments,
    std::size_t maximum_resting_orders_per_instrument,
    std::int32_t owned_partition)
    : maximum_resting_orders_per_instrument_(maximum_resting_orders_per_instrument),
      owned_partition_(owned_partition) {
  if (assigned_instruments.empty() || assigned_instruments.size() > kMaximumBooks ||
      maximum_resting_orders_per_instrument == 0 || owned_partition < 0) {
    throw std::invalid_argument("invalid fixed matching-core capacity or owned partition");
  }
  books_.reserve(assigned_instruments.size());
  for (const auto &instrument : assigned_instruments) {
    if (find_book(instrument) != nullptr) {
      throw std::invalid_argument("matching core received duplicate assigned instrument");
    }
    books_.emplace_back(instrument, maximum_resting_orders_per_instrument);
  }
  events_.reserve(books_.size() * maximum_resting_orders_per_instrument_ + 1);
}

MatchingProcessResult DeterministicMatchingCore::process(const CoreCommand &command) {
  events_.clear();
  if (command.context.partition_id != owned_partition_) {
    return MatchingProcessResult::kPartitionMismatch;
  }
  if (!valid_context(command.context, owned_partition_)) {
    return MatchingProcessResult::kInvalidCommand;
  }
  if (command.type == CoreCommandType::kOpenBarrier) {
    return process_open_barrier(command);
  }
  if (command.type == CoreCommandType::kCloseBarrier) {
    process_close_barrier(command);
    return MatchingProcessResult::kApplied;
  }
  const CoreInstrument &instrument =
      command.type == CoreCommandType::kNewOrder ? command.new_order().instrument
                                                  : command.cancel_order().instrument;
  Book *book = find_book(instrument);
  if (book == nullptr) {
    return MatchingProcessResult::kUnknownInstrument;
  }
  return command.type == CoreCommandType::kNewOrder ? process_new_order(command, *book)
                                                      : process_cancel_order(command, *book);
}

MatchingProcessResult DeterministicMatchingCore::process_open_barrier(
    const CoreCommand &command) const {
  return command.expected_partition_count == 15 ? MatchingProcessResult::kApplied
                                                : MatchingProcessResult::kInvalidCommand;
}

std::span<const CoreEvent> DeterministicMatchingCore::events() const {
  return events_;
}

std::size_t DeterministicMatchingCore::resting_order_count(const CoreInstrument &instrument) const {
  const Book *book = find_book(instrument);
  return book == nullptr ? 0 : book->buys.size() + book->sells.size();
}

std::size_t DeterministicMatchingCore::maximum_output_events() const {
  return books_.size() * maximum_resting_orders_per_instrument_ + 1;
}

DeterministicMatchingCore::Book *DeterministicMatchingCore::find_book(
    const CoreInstrument &instrument) {
  const auto found = std::find_if(books_.begin(), books_.end(), [&](const Book &book) {
    return same_instrument(book.instrument, instrument);
  });
  return found == books_.end() ? nullptr : &*found;
}

const DeterministicMatchingCore::Book *DeterministicMatchingCore::find_book(
    const CoreInstrument &instrument) const {
  const auto found = std::find_if(books_.begin(), books_.end(), [&](const Book &book) {
    return same_instrument(book.instrument, instrument);
  });
  return found == books_.end() ? nullptr : &*found;
}

MatchingProcessResult DeterministicMatchingCore::process_new_order(
    const CoreCommand &command, Book &book) {
  const CoreNewOrder &incoming = command.new_order();
  if (incoming.quantity.value() <= 0 ||
      (incoming.order_type == CoreOrderType::kLimit && incoming.limit_price.value() <= 0)) {
    return MatchingProcessResult::kInvalidCommand;
  }
  std::vector<RestingOrder> &same_side =
      incoming.side == CoreSide::kBuy ? book.buys : book.sells;
  std::vector<RestingOrder> &opposite_side =
      incoming.side == CoreSide::kBuy ? book.sells : book.buys;
  const auto crosses = [&](const RestingOrder &resting) {
    if (incoming.order_type == CoreOrderType::kMarket) {
      return true;
    }
    return incoming.side == CoreSide::kBuy ? incoming.limit_price.value() >= resting.price.value()
                                            : incoming.limit_price.value() <= resting.price.value();
  };
  if (incoming.order_type == CoreOrderType::kLimit &&
      incoming.time_in_force == CoreTimeInForce::kRod &&
      same_side.size() == maximum_resting_orders_per_instrument_) {
    return MatchingProcessResult::kOrderBookFull;
  }

  if (incoming.time_in_force == CoreTimeInForce::kFok) {
    std::int64_t available = 0;
    for (const RestingOrder &resting : opposite_side) {
      if (!crosses(resting)) {
        break;
      }
      if (available >= incoming.quantity.value() - resting.remaining.value()) {
        available = incoming.quantity.value();
        break;
      }
      available += resting.remaining.value();
    }
    if (available < incoming.quantity.value()) {
      CoreEvent event{};
      event.type = CoreEventType::kOrderCancelled;
      event.event_id = event_id(command.context, incoming.command_id, 0);
      event.output_index = 0;
      event.source_command_id = incoming.command_id;
      event.order_id = incoming.order_id;
      event.account_id = incoming.account_id;
      event.instrument = incoming.instrument;
      event.side = incoming.side;
      event.leaves_quantity = incoming.quantity;
      event.cancellation_reason = CoreCancellationReason::kFokNotFilled;
      events_.push_back(event);
      return MatchingProcessResult::kApplied;
    }
  }

  std::int64_t remaining = incoming.quantity.value();
  unsigned __int128 taker_notional = 0;
  std::int32_t output_index = 0;
  std::int32_t match_index = 0;
  while (remaining > 0 && !opposite_side.empty() && crosses(opposite_side.front())) {
    RestingOrder &maker = opposite_side.front();
    const std::int64_t matched = std::min(remaining, maker.remaining.value());
    CoreEvent event{};
    event.type = CoreEventType::kTradeExecuted;
    const std::int32_t current_output_index = output_index++;
    const std::int32_t current_match_index = match_index++;
    event.event_id = event_id(command.context, incoming.command_id, current_output_index);
    event.trade_id = trade_id(command.context, incoming.command_id, current_match_index);
    event.output_index = current_output_index;
    event.source_command_id = incoming.command_id;
    event.maker_order_id = maker.order_id;
    event.maker_account_id = maker.account_id;
    event.taker_order_id = incoming.order_id;
    event.taker_account_id = incoming.account_id;
    event.instrument = incoming.instrument;
    event.maker_side = maker.side;
    event.taker_side = incoming.side;
    event.quantity = ShareQuantity(matched);
    event.price = maker.price;
    event.maker_cumulative_quantity =
        ShareQuantity(maker.original_quantity.value() - maker.remaining.value() + matched);
    event.maker_leaves_quantity = ShareQuantity(maker.remaining.value() - matched);
    event.taker_cumulative_quantity =
        ShareQuantity(incoming.quantity.value() - remaining + matched);
    event.taker_leaves_quantity = ShareQuantity(remaining - matched);
    event.maker_average_price = maker.price;
    taker_notional += static_cast<unsigned __int128>(matched) *
                       static_cast<unsigned __int128>(maker.price.value());
    event.taker_average_price =
        FixedPrice(static_cast<std::int64_t>(
            taker_notional / static_cast<unsigned __int128>(event.taker_cumulative_quantity.value())));
    event.match_index = current_match_index;
    events_.push_back(event);
    remaining -= matched;
    const std::int64_t maker_remaining = maker.remaining.value() - matched;
    if (maker_remaining == 0) {
      opposite_side.erase(opposite_side.begin());
    } else {
      maker.remaining = ShareQuantity(maker_remaining);
    }
  }

  if (remaining == 0) {
    return MatchingProcessResult::kApplied;
  }
  if (incoming.order_type == CoreOrderType::kLimit && incoming.time_in_force == CoreTimeInForce::kRod) {
    RestingOrder resting{incoming.order_id,
                         incoming.account_id,
                         incoming.side,
                         incoming.quantity,
                         ShareQuantity(remaining),
                         incoming.limit_price,
                         arrival_sequence_++};
    const auto before = std::find_if(same_side.begin(), same_side.end(), [&](const RestingOrder &entry) {
      if (incoming.side == CoreSide::kBuy) {
        return resting.price.value() > entry.price.value() ||
               (resting.price.value() == entry.price.value() &&
                resting.arrival_sequence < entry.arrival_sequence);
      }
      return resting.price.value() < entry.price.value() ||
             (resting.price.value() == entry.price.value() &&
              resting.arrival_sequence < entry.arrival_sequence);
    });
    same_side.insert(before, resting);
    CoreEvent event{};
    event.type = CoreEventType::kOrderRested;
    event.event_id = event_id(command.context, incoming.command_id, output_index);
    event.output_index = output_index;
    event.source_command_id = incoming.command_id;
    event.order_id = incoming.order_id;
    event.account_id = incoming.account_id;
    event.instrument = incoming.instrument;
    event.side = incoming.side;
    event.leaves_quantity = ShareQuantity(remaining);
    event.price = incoming.limit_price;
    events_.push_back(event);
    return MatchingProcessResult::kApplied;
  }

  CoreEvent event{};
  event.type = CoreEventType::kOrderCancelled;
  event.event_id = event_id(command.context, incoming.command_id, output_index);
  event.output_index = output_index;
  event.source_command_id = incoming.command_id;
  event.order_id = incoming.order_id;
  event.account_id = incoming.account_id;
  event.instrument = incoming.instrument;
  event.side = incoming.side;
  event.leaves_quantity = ShareQuantity(remaining);
  event.cancellation_reason =
      incoming.time_in_force == CoreTimeInForce::kFok
          ? CoreCancellationReason::kFokNotFilled
          : CoreCancellationReason::kIocRemainder;
  events_.push_back(event);
  return MatchingProcessResult::kApplied;
}

MatchingProcessResult DeterministicMatchingCore::process_cancel_order(
    const CoreCommand &command, Book &book) {
  const CoreCancelOrder &cancel = command.cancel_order();
  std::vector<RestingOrder> &orders = cancel.side == CoreSide::kBuy ? book.buys : book.sells;
  const auto found = std::find_if(orders.begin(), orders.end(), [&](const RestingOrder &entry) {
    return entry.order_id == cancel.order_id;
  });
  if (found == orders.end()) {
    return MatchingProcessResult::kApplied;
  }
  CoreEvent event{};
  event.type = CoreEventType::kOrderCancelled;
  event.event_id = event_id(command.context, cancel.command_id, 0);
  event.output_index = 0;
  event.source_command_id = cancel.command_id;
  event.order_id = cancel.order_id;
  event.account_id = cancel.account_id;
  event.instrument = cancel.instrument;
  event.side = cancel.side;
  event.leaves_quantity = found->remaining;
  event.cancellation_reason = CoreCancellationReason::kUserRequest;
  events_.push_back(event);
  orders.erase(found);
  return MatchingProcessResult::kApplied;
}

void DeterministicMatchingCore::process_close_barrier(const CoreCommand &command) {
  std::int32_t output_index = 0;
  for (Book &book : books_) {
    const auto expire = [&](std::vector<RestingOrder> &orders) {
      for (const RestingOrder &resting : orders) {
        CoreEvent event{};
        event.type = CoreEventType::kOrderExpired;
        event.event_id = event_id(command.context, command.command_id, output_index++);
        event.output_index = output_index - 1;
        event.source_command_id = command.command_id;
        event.order_id = resting.order_id;
        event.account_id = resting.account_id;
        event.instrument = book.instrument;
        event.side = resting.side;
        event.leaves_quantity = resting.remaining;
        event.cancellation_reason = CoreCancellationReason::kSessionExpired;
        events_.push_back(event);
      }
      orders.clear();
    };
    expire(book.buys);
    expire(book.sells);
  }
}

} // namespace simplematch::matching
