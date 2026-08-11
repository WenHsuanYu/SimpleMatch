#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace simplematch::matching {

/** Fixed sixteen-byte identity used by the matching hot path. */
struct CoreUuid {
  std::array<std::uint8_t, 16> bytes{};

  static std::optional<CoreUuid> parse(std::string_view text);

  bool operator==(const CoreUuid &) const = default;
};

/** Positive whole-share quantity used in matching arithmetic. */
class ShareQuantity {
public:
  explicit ShareQuantity(std::int64_t value);

  [[nodiscard]] std::int64_t value() const;

  bool operator==(const ShareQuantity &) const = default;

private:
  std::int64_t value_;
};

/** TWD price in one ten-thousandth TWD unit. */
class FixedPrice {
public:
  explicit FixedPrice(std::int64_t value);

  [[nodiscard]] std::int64_t value() const;

  bool operator==(const FixedPrice &) const = default;

private:
  std::int64_t value_;
};

/** Fixed-size XTAI or ROCO instrument key with no hot-path string allocation. */
struct CoreInstrument {
  std::array<char, 4> venue{};
  std::array<char, 12> symbol{};
  std::uint8_t symbol_length{};

  static std::optional<CoreInstrument> create(std::string_view venue, std::string_view symbol);

  bool operator==(const CoreInstrument &) const = default;
};

/** Fixed-capacity text copied at the infrastructure boundary before entering the matching ring. */
template <std::size_t Capacity>
struct FixedText {
  std::array<char, Capacity> bytes{};
  std::size_t length{};

  static std::optional<FixedText> create(std::string_view value) {
    if (value.empty() || value.size() > Capacity) {
      return std::nullopt;
    }
    FixedText text;
    for (std::size_t index = 0; index < value.size(); ++index) {
      text.bytes[index] = value[index];
    }
    text.length = value.size();
    return text;
  }

  [[nodiscard]] std::string_view view() const {
    return {bytes.data(), length};
  }

  [[nodiscard]] bool empty() const {
    return length == 0;
  }

  bool operator==(const FixedText &) const = default;
};

enum class CoreSide { kBuy, kSell };
enum class CoreOrderType { kLimit, kMarket };
enum class CoreTimeInForce { kRod, kIoc, kFok };
enum class CoreCommandType { kNewOrder, kCancelOrder, kOpenBarrier, kCloseBarrier };
enum class CoreEventType { kOrderRested, kTradeExecuted, kOrderCancelled, kOrderExpired };
enum class CoreCancellationReason {
  kNone,
  kUserRequest,
  kIocRemainder,
  kFokNotFilled,
  kSessionExpired
};
enum class MatchingProcessResult {
  kApplied,
  kPartitionMismatch,
  kInvalidCommand,
  kUnknownInstrument,
  kOrderBookFull
};

/** Immutable context that makes each command's output identities deterministic. */
struct MatchingCommandContext {
  FixedText<96> artifact_identity;
  FixedText<64> trading_session_id;
  FixedText<64> routing_algorithm_version;
  std::int32_t partition_id;

  static std::optional<MatchingCommandContext> create(
      std::string_view artifact_identity,
      std::string_view trading_session_id,
      std::string_view routing_algorithm_version,
      std::int32_t partition_id);

  bool operator==(const MatchingCommandContext &) const = default;
};

/** Fully decoded new order passed into the infrastructure-free matching core. */
struct CoreNewOrder {
  CoreUuid command_id{};
  CoreUuid order_id{};
  CoreUuid account_id{};
  CoreInstrument instrument{};
  CoreSide side{CoreSide::kBuy};
  ShareQuantity quantity{1};
  FixedPrice limit_price{0};
  CoreOrderType order_type{CoreOrderType::kLimit};
  CoreTimeInForce time_in_force{CoreTimeInForce::kRod};

  bool operator==(const CoreNewOrder &) const = default;
};

/** Fully decoded cancellation passed into the infrastructure-free matching core. */
struct CoreCancelOrder {
  CoreUuid command_id{};
  CoreUuid order_id{};
  CoreUuid account_id{};
  CoreInstrument instrument{};
  CoreSide side{CoreSide::kBuy};

  bool operator==(const CoreCancelOrder &) const = default;
};

/** Decoded command with a typed payload and immutable identity context. */
struct CoreCommand {
  MatchingCommandContext context;
  CoreCommandType type;
  CoreUuid command_id{};
  std::int32_t expected_partition_count{};
  CoreNewOrder new_order_payload{};
  CoreCancelOrder cancel_order_payload{};

  static CoreCommand new_order(
      MatchingCommandContext context,
      CoreUuid command_id,
      CoreUuid order_id,
      CoreUuid account_id,
      CoreInstrument instrument,
      CoreSide side,
      ShareQuantity quantity,
      FixedPrice limit_price,
      CoreOrderType order_type,
      CoreTimeInForce time_in_force);
  static CoreCommand open(
      MatchingCommandContext context, CoreUuid command_id, std::int32_t expected_partition_count);
  static CoreCommand cancel(
      MatchingCommandContext context,
      CoreUuid command_id,
      CoreUuid order_id,
      CoreUuid account_id,
      CoreInstrument instrument,
      CoreSide side);
  static CoreCommand close(MatchingCommandContext context, CoreUuid command_id = {});

  [[nodiscard]] const CoreNewOrder &new_order() const;
  [[nodiscard]] const CoreCancelOrder &cancel_order() const;
};

/** Fixed-value result event consumed by a publisher outside the matching core. */
struct CoreEvent {
  CoreEventType type{CoreEventType::kOrderRested};
  std::array<char, 65> event_id{};
  std::array<char, 65> trade_id{};
  CoreUuid source_command_id{};
  CoreUuid order_id{};
  CoreUuid account_id{};
  CoreUuid maker_order_id{};
  CoreUuid maker_account_id{};
  CoreUuid taker_order_id{};
  CoreUuid taker_account_id{};
  CoreInstrument instrument{};
  CoreSide side{CoreSide::kBuy};
  CoreSide maker_side{CoreSide::kBuy};
  CoreSide taker_side{CoreSide::kBuy};
  ShareQuantity quantity{1};
  ShareQuantity leaves_quantity{1};
  ShareQuantity maker_cumulative_quantity{0};
  ShareQuantity maker_leaves_quantity{0};
  ShareQuantity taker_cumulative_quantity{0};
  ShareQuantity taker_leaves_quantity{0};
  FixedPrice maker_average_price{0};
  FixedPrice taker_average_price{0};
  FixedPrice price{0};
  CoreCancellationReason cancellation_reason{CoreCancellationReason::kNone};
  std::int32_t output_index{};
  std::int32_t match_index{};

  bool operator==(const CoreEvent &) const = default;
};

/**
 * Single-writer deterministic order-book core for the instrument set assigned to one partition.
 *
 * <p>All vectors reserve their complete capacity in the constructor. {@link process} performs no
 * I/O, locking, networking, allocation-backed book creation, or capacity expansion.
 */
class DeterministicMatchingCore {
public:
  DeterministicMatchingCore(
      std::vector<CoreInstrument> assigned_instruments,
      std::size_t maximum_resting_orders_per_instrument,
      std::int32_t owned_partition);
  ~DeterministicMatchingCore();

  DeterministicMatchingCore(const DeterministicMatchingCore &) = delete;
  DeterministicMatchingCore &operator=(const DeterministicMatchingCore &) = delete;

  [[nodiscard]] MatchingProcessResult process(const CoreCommand &command);
  [[nodiscard]] std::span<const CoreEvent> events() const;
  [[nodiscard]] std::size_t resting_order_count(const CoreInstrument &instrument) const;
  [[nodiscard]] std::size_t maximum_output_events() const;

private:
  struct RestingOrder;
  struct Book;

  [[nodiscard]] Book *find_book(const CoreInstrument &instrument);
  [[nodiscard]] const Book *find_book(const CoreInstrument &instrument) const;
  [[nodiscard]] MatchingProcessResult process_new_order(const CoreCommand &command, Book &book);
  [[nodiscard]] MatchingProcessResult process_cancel_order(const CoreCommand &command, Book &book);
  [[nodiscard]] MatchingProcessResult process_open_barrier(const CoreCommand &command) const;
  void process_close_barrier(const CoreCommand &command);

  std::vector<Book> books_;
  std::vector<CoreEvent> events_;
  std::size_t maximum_resting_orders_per_instrument_;
  std::int32_t owned_partition_;
  std::uint64_t arrival_sequence_{};
};

} // namespace simplematch::matching
