#include <chrono>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/matching_event_encoder.hpp"

#ifdef __linux__
#include <sys/resource.h>
#endif

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::size_t kInstrumentCount = 150;
constexpr std::size_t kRestingPerSide = 1024;
constexpr std::size_t kRestingTotal = kInstrumentCount * 2 * kRestingPerSide;
constexpr std::int64_t kQuantity = 1;
constexpr std::int64_t kBuyPrice = 1'000'000;
constexpr std::int64_t kSellPrice = 2'000'000;
constexpr std::string_view kArtifactIdentity =
    "2026-08-11:"
    "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";

struct Checksum {
  std::uint64_t value{1469598103934665603ULL};

  void byte(std::uint8_t item) {
    value ^= item;
    value *= 1099511628211ULL;
  }

  void integer(std::uint64_t item) {
    for (std::size_t index = 0; index < sizeof(item); ++index) {
      byte(static_cast<std::uint8_t>(item >> (8 * index)));
    }
  }

  void uuid(const CoreUuid& item) {
    for (const std::uint8_t octet : item.bytes) {
      byte(octet);
    }
  }

  void instrument(const CoreInstrument& item) {
    for (const char character : item.venue) {
      byte(static_cast<std::uint8_t>(character));
    }
    integer(item.symbol_length);
    for (std::size_t index = 0; index < item.symbol_length; ++index) {
      byte(static_cast<std::uint8_t>(item.symbol[index]));
    }
  }

  void text(std::string_view item) {
    integer(item.size());
    for (const unsigned char character : item) {
      byte(character);
    }
  }

  // Independent semantic checksum from submitted commands, before
  // core.process().
  void expected(const CoreCommand& command) {
    const CoreNewOrder& order = command.new_order();
    uuid(command.command_id);
    uuid(order.order_id);
    uuid(order.account_id);
    instrument(order.instrument);
    integer(static_cast<std::uint64_t>(order.side));
    integer(static_cast<std::uint64_t>(order.quantity.value()));
    integer(static_cast<std::uint64_t>(order.limit_price.value()));
  }

  void observed(const CoreEvent& event) {
    uuid(event.source_command_id);
    uuid(event.order_id);
    uuid(event.account_id);
    instrument(event.instrument);
    integer(static_cast<std::uint64_t>(event.side));
    integer(static_cast<std::uint64_t>(event.leaves_quantity.value()));
    integer(static_cast<std::uint64_t>(event.price.value()));
  }

  void record(const MatchingEventRecord& item) {
    text(item.key);
    text(item.value);
    integer(static_cast<std::uint64_t>(item.partition_id));
    integer(static_cast<std::uint64_t>(item.source_input_offset));
    integer(static_cast<std::uint64_t>(item.output_index));
  }
};

// Fixed UUIDv7 timestamp/version and RFC 4122 variant; role + monotonically
// increasing 48-bit serial make all command/order/account IDs distinct.
CoreUuid uuid(std::uint64_t serial, std::uint8_t role) {
  CoreUuid result{};
  result.bytes[0] = 0x01;
  result.bytes[1] = 0x98;
  result.bytes[2] = 0xa0;
  result.bytes[3] = 0x01;
  result.bytes[6] = 0x70;
  result.bytes[7] = role;
  result.bytes[8] = 0x80;
  for (std::size_t index = 0; index < 6; ++index) {
    result.bytes[15 - index] = static_cast<std::uint8_t>(serial >> (8 * index));
  }
  return result;
}

std::vector<CoreInstrument> instruments(std::size_t count) {
  std::vector<CoreInstrument> result;
  result.reserve(count);
  for (std::size_t index = 0; index < count; ++index) {
    const auto instrument =
        CoreInstrument::create("XTAI", "E" + std::to_string(index));
    if (!instrument.has_value()) {
      throw std::logic_error("invalid capacity envelope instrument fixture");
    }
    result.push_back(*instrument);
  }
  return result;
}

MatchingCommandContext context() {
  return MatchingCommandContext::create(kArtifactIdentity, "2026-08-11-regular",
                                        "stable-least-loaded-v1", 0)
      .value();
}

CoreCommand order(const MatchingCommandContext& command_context,
                  const CoreInstrument& instrument, CoreSide side,
                  std::uint64_t serial) {
  return CoreCommand::new_order(
      command_context, uuid(serial, 0x11), uuid(serial, 0x22),
      uuid(serial, 0x33), instrument, side, ShareQuantity(kQuantity),
      FixedPrice(side == CoreSide::kBuy ? kBuyPrice : kSellPrice),
      CoreOrderType::kLimit, CoreTimeInForce::kRod);
}

struct ReplaySummary {
  Checksum expected_commands;
  Checksum observed_events;
  Checksum serialized_records;
  std::uint64_t state_checksum{};
  std::size_t rested{};
  std::size_t rejected{};
  std::size_t trades{};
  std::int64_t elapsed_ms{};
};

// One core per invocation; the caller discards it before the sequential replay.
void fill_envelope(ReplaySummary* summary) {
  const auto start = std::chrono::steady_clock::now();
  const auto keys = instruments(kInstrumentCount);
  const auto command_context = context();
  DeterministicMatchingCore core(keys, kRestingPerSide, 0);
  MatchingEventEncoder encoder;
  std::unordered_set<std::string> event_keys;
  event_keys.reserve(kRestingTotal);
  ASSERT_EQ(core.maximum_output_events(), kRestingTotal + 1);
  ASSERT_EQ(core.process(CoreCommand::open(command_context, uuid(0, 0x44), 15)),
            MatchingProcessResult::kApplied);
  ASSERT_TRUE(core.events().empty());

  std::uint64_t serial = 0;
  for (const CoreInstrument& instrument : keys) {
    for (const CoreSide side : {CoreSide::kBuy, CoreSide::kSell}) {
      for (std::size_t index = 0; index < kRestingPerSide; ++index) {
        const auto command = order(command_context, instrument, side, ++serial);
        summary->expected_commands.expected(command);
        ASSERT_EQ(core.process(command), MatchingProcessResult::kApplied)
            << "serial=" << serial;
        ASSERT_EQ(core.events().size(), 1U) << "serial=" << serial;
        const CoreEvent& event = core.events().front();
        if (event.type == CoreEventType::kTradeExecuted) {
          ++summary->trades;
        }
        ASSERT_EQ(event.type, CoreEventType::kOrderRested)
            << "serial=" << serial;
        ASSERT_EQ(event.source_command_id, command.command_id);
        ASSERT_EQ(event.order_id, command.new_order().order_id);
        ASSERT_EQ(event.account_id, command.new_order().account_id);
        ASSERT_EQ(event.instrument, instrument);
        ASSERT_EQ(event.side, side);
        ASSERT_EQ(event.leaves_quantity.value(), kQuantity);
        ASSERT_EQ(event.price.value(),
                  side == CoreSide::kBuy ? kBuyPrice : kSellPrice);
        ASSERT_EQ(event.output_index, 0);
        summary->observed_events.observed(event);

        const auto encoded = encoder.encode(
            command_context, static_cast<std::int64_t>(100 + serial), event);
        ASSERT_TRUE(encoded.has_value()) << "serial=" << serial;
        ASSERT_EQ(encoded->key.size(), 32U);
        ASSERT_TRUE(event_keys.insert(encoded->key).second)
            << "serial=" << serial;
        ASSERT_FALSE(encoded->value.empty());
        ASSERT_EQ(encoded->partition_id, 0);
        ASSERT_EQ(encoded->source_input_offset,
                  static_cast<std::int64_t>(100 + serial));
        ASSERT_EQ(encoded->output_index, 0);
        summary->serialized_records.record(*encoded);
        ++summary->rested;
      }

      const std::size_t expected_resting =
          (side == CoreSide::kBuy ? 1 : 2) * kRestingPerSide;
      ASSERT_EQ(core.resting_order_count(instrument), expected_resting);
      const auto extra = order(command_context, instrument, side, ++serial);
      // Verify state preservation on both sides at the first and final book.
      // Rehashing the entire growing book for all 300 probes is quadratic.
      const bool check_state =
          &instrument == &keys.front() || &instrument == &keys.back();
      const auto checksum_before_rejection =
          check_state ? core.deterministic_state_checksum() : 0;
      ASSERT_EQ(core.process(extra), MatchingProcessResult::kOrderBookFull)
          << "overflow serial=" << serial;
      ASSERT_TRUE(core.events().empty());
      ASSERT_EQ(core.resting_order_count(instrument), expected_resting);
      if (check_state) {
        ASSERT_EQ(core.deterministic_state_checksum(),
                  checksum_before_rejection);
      }
      ++summary->rejected;
    }
  }

  ASSERT_EQ(summary->rested, kRestingTotal);
  ASSERT_EQ(event_keys.size(), kRestingTotal);
  ASSERT_EQ(summary->rejected, kInstrumentCount * 2);
  ASSERT_EQ(serial, kRestingTotal + kInstrumentCount * 2);
  ASSERT_EQ(summary->trades, 0U);
  ASSERT_EQ(summary->observed_events.value, summary->expected_commands.value);
  summary->state_checksum = core.deterministic_state_checksum();
  summary->elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start)
                            .count();
}

TEST(MatchingCapacityEnvelopeTest, RejectsExtraRestingOrderOnEachSide) {
  const auto key = instruments(1).front();
  const auto command_context = context();
  DeterministicMatchingCore core({key}, 1, 0);
  ASSERT_EQ(core.process(order(command_context, key, CoreSide::kBuy, 1)),
            MatchingProcessResult::kApplied);
  ASSERT_EQ(core.process(order(command_context, key, CoreSide::kSell, 2)),
            MatchingProcessResult::kApplied);
  EXPECT_EQ(core.resting_order_count(key), 2U);
  EXPECT_EQ(core.process(order(command_context, key, CoreSide::kBuy, 3)),
            MatchingProcessResult::kOrderBookFull);
  EXPECT_TRUE(core.events().empty());
  EXPECT_EQ(core.process(order(command_context, key, CoreSide::kSell, 4)),
            MatchingProcessResult::kOrderBookFull);
  EXPECT_TRUE(core.events().empty());
  EXPECT_EQ(core.resting_order_count(key), 2U);
}

TEST(MatchingCapacityEnvelopeTest, RejectsUnsupportedCoreCapacity) {
  auto keys = instruments(kInstrumentCount + 1);
  EXPECT_THROW((DeterministicMatchingCore(keys, kRestingPerSide, 0)),
               std::invalid_argument);
  keys.pop_back();
  EXPECT_THROW((DeterministicMatchingCore(keys, 0, 0)), std::invalid_argument);
}

TEST(MatchingCapacityEnvelopeTest,
     ComputesManagedUpperBoundsForApprovedPartitions) {
  struct Case {
    std::size_t books;
    std::size_t maximum_events;
  };
  constexpr Case cases[]{{128, 1'048'577}, {129, 1'056'769}, {150, 1'228'801}};
  for (const Case& sample : cases) {
    SCOPED_TRACE(sample.books);
    DeterministicMatchingCore core(instruments(sample.books), 4096, 0);
    EXPECT_EQ(core.maximum_output_events(), sample.maximum_events);
  }
}

TEST(MatchingCapacityEnvelopeTest,
     Saturates150InstrumentsAndReplaysIdentically) {
  ReplaySummary first;
  ASSERT_NO_FATAL_FAILURE(fill_envelope(&first));
  ReplaySummary replay;
  ASSERT_NO_FATAL_FAILURE(fill_envelope(&replay));
  EXPECT_EQ(first.expected_commands.value, replay.expected_commands.value);
  EXPECT_EQ(first.observed_events.value, replay.observed_events.value);
  EXPECT_EQ(first.serialized_records.value, replay.serialized_records.value);
  EXPECT_EQ(first.state_checksum, replay.state_checksum);
  EXPECT_EQ(first.rested, replay.rested);
  EXPECT_EQ(first.rejected, replay.rejected);
  EXPECT_EQ(first.trades, replay.trades);

  long peak_rss_kib = 0;
#ifdef __linux__
  struct rusage usage{};
  if (getrusage(RUSAGE_SELF, &usage) == 0) {
    peak_rss_kib = usage.ru_maxrss;
  }
#endif
  std::cout << "core_only_capacity_envelope: instruments=" << kInstrumentCount
            << " resting_per_side=" << kRestingPerSide
            << " accepted_core_commands=" << first.rested + 1
            << " rested=" << first.rested << " rejected=" << first.rejected
            << " trades=" << first.trades << " first_ms=" << first.elapsed_ms
            << " replay_ms=" << replay.elapsed_ms
            << " peak_process_rss_kib=" << peak_rss_kib
            << " commands_checksum=" << first.expected_commands.value
            << " records_checksum=" << first.serialized_records.value
            << " state_checksum=" << first.state_checksum << '\n';
}

}  // namespace
}  // namespace simplematch::matching
