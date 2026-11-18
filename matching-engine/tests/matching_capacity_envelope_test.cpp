#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
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

  void staged_expected(const CoreCommand& command) {
    if (command.type == CoreCommandType::kNewOrder) {
      integer(static_cast<std::uint64_t>(CoreEventType::kOrderRested));
      expected(command);
    } else {
      const CoreCancelOrder& cancel = command.cancel_order();
      integer(static_cast<std::uint64_t>(CoreEventType::kOrderCancelled));
      uuid(command.command_id);
      uuid(cancel.order_id);
      uuid(cancel.account_id);
      instrument(cancel.instrument);
      integer(static_cast<std::uint64_t>(cancel.side));
      integer(static_cast<std::uint64_t>(kQuantity));
      integer(0);  // Cancellation does not have a transaction price.
    }
  }

  void staged_observed(const CoreEvent& event) {
    integer(static_cast<std::uint64_t>(event.type));
    observed(event);
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

constexpr std::size_t kHighRestingPerSide = 4096;
enum class StagedPhase : std::uint8_t { kFill, kCancel, kRefill };
constexpr std::array kStagedPhases{StagedPhase::kFill, StagedPhase::kCancel,
                                   StagedPhase::kRefill};
constexpr std::size_t kStagedPhaseCount = kStagedPhases.size();

struct StagedSummary {
  Checksum expected_events;
  Checksum observed_events;
  Checksum serialized_records;
  std::array<std::size_t, kStagedPhaseCount> phase_events{};
  std::array<std::uint64_t, kStagedPhaseCount> phase_record_checksums{};
  std::array<std::uint64_t, kStagedPhaseCount> phase_state_checksums{};
  std::uint64_t commands{};
  std::size_t trades{};
  std::int64_t elapsed_ms{};
};

struct StagedOrderSlot {
  const CoreInstrument& instrument;
  CoreSide side;
  std::uint64_t initial_order_serial;
};

CoreCommand staged_command(StagedPhase phase,
                           const MatchingCommandContext& command_context,
                           const StagedOrderSlot& slot,
                           std::uint64_t command_serial) {
  if (phase == StagedPhase::kCancel) {
    return CoreCommand::cancel(command_context, uuid(command_serial, 0x11),
                               uuid(slot.initial_order_serial, 0x22),
                               uuid(slot.initial_order_serial, 0x33),
                               slot.instrument, slot.side);
  }
  return order(command_context, slot.instrument, slot.side, command_serial);
}

void expect_staged_event(const CoreCommand& command, const CoreEvent& event,
                         StagedPhase phase, const StagedOrderSlot& slot,
                         std::uint64_t command_serial) {
  const bool cancelling = phase == StagedPhase::kCancel;
  ASSERT_EQ(event.type, cancelling ? CoreEventType::kOrderCancelled
                                   : CoreEventType::kOrderRested)
      << "command_serial=" << command_serial;
  ASSERT_EQ(event.source_command_id, command.command_id);
  const std::uint64_t order_serial =
      cancelling ? slot.initial_order_serial : command_serial;
  ASSERT_EQ(event.order_id, uuid(order_serial, 0x22));
  ASSERT_EQ(event.account_id, uuid(order_serial, 0x33));
  ASSERT_EQ(event.instrument, slot.instrument);
  ASSERT_EQ(event.side, slot.side);
  ASSERT_EQ(event.leaves_quantity.value(), kQuantity);
  ASSERT_EQ(event.price.value(), cancelling                    ? 0
                                 : slot.side == CoreSide::kBuy ? kBuyPrice
                                                               : kSellPrice);
  ASSERT_EQ(event.output_index, 0);
  if (cancelling) {
    ASSERT_EQ(event.cancellation_reason, CoreCancellationReason::kUserRequest);
  }
}

void expect_staged_encoding(MatchingEventEncoder& encoder,
                            const MatchingCommandContext& command_context,
                            const CoreEvent& event,
                            std::uint64_t command_serial,
                            Checksum* serialized_records) {
  const auto source_offset = static_cast<std::int64_t>(100 + command_serial);
  const auto encoded = encoder.encode(command_context, source_offset, event);
  ASSERT_TRUE(encoded.has_value()) << "command_serial=" << command_serial;
  ASSERT_EQ(encoded->key.size(), 32U);
  ASSERT_FALSE(encoded->value.empty());
  ASSERT_EQ(encoded->partition_id, 0);
  ASSERT_EQ(encoded->source_input_offset, source_offset);
  ASSERT_EQ(encoded->output_index, 0);
  serialized_records->record(*encoded);
}

// No command or event transcript is retained. The initial fill's order IDs are
// reconstructed by position for cancellation, so memory scales with live books.
void run_staged_envelope(std::size_t book_count, StagedSummary* summary) {
  const auto start = std::chrono::steady_clock::now();
  const auto keys = instruments(book_count);
  const auto command_context = context();
  DeterministicMatchingCore core(keys, kHighRestingPerSide, 0);
  MatchingEventEncoder encoder;
  const std::size_t orders_per_phase = book_count * 2 * kHighRestingPerSide;
  ASSERT_EQ(core.maximum_output_events(), orders_per_phase + 1);
  ASSERT_EQ(core.process(CoreCommand::open(command_context, uuid(0, 0x44), 15)),
            MatchingProcessResult::kApplied);
  ASSERT_TRUE(core.events().empty());
  summary->commands = 1;

  std::uint64_t serial = 0;
  for (std::size_t phase_index = 0; phase_index < kStagedPhaseCount;
       ++phase_index) {
    const StagedPhase phase = kStagedPhases[phase_index];
    for (std::size_t book = 0; book < book_count; ++book) {
      const CoreInstrument& instrument = keys[book];
      for (std::size_t side_index = 0; side_index < 2; ++side_index) {
        const CoreSide side =
            side_index == 0 ? CoreSide::kBuy : CoreSide::kSell;
        for (std::size_t index = 0; index < kHighRestingPerSide; ++index) {
          // The first fill is serials [1, orders_per_phase]. Later new orders
          // and cancel commands use fresh serials, never recycled identities.
          const StagedOrderSlot slot{
              instrument, side,
              1 + (book * 2 + side_index) * kHighRestingPerSide + index};
          const std::uint64_t command_serial = ++serial;
          const CoreCommand command =
              staged_command(phase, command_context, slot, command_serial);

          summary->expected_events.staged_expected(command);
          ASSERT_EQ(core.process(command), MatchingProcessResult::kApplied)
              << "phase_index=" << phase_index
              << " command_serial=" << command_serial;
          ASSERT_EQ(core.events().size(), 1U)
              << "command_serial=" << command_serial;
          const CoreEvent& event = core.events().front();
          if (event.type == CoreEventType::kTradeExecuted) {
            ++summary->trades;
          }
          ASSERT_NO_FATAL_FAILURE(
              expect_staged_event(command, event, phase, slot, command_serial));
          summary->observed_events.staged_observed(event);

          ASSERT_NO_FATAL_FAILURE(expect_staged_encoding(
              encoder, command_context, event, command_serial,
              &summary->serialized_records));
          ++summary->phase_events[phase_index];
          ++summary->commands;
        }
      }
      ASSERT_EQ(core.resting_order_count(instrument),
                phase == StagedPhase::kCancel ? 0U : 2 * kHighRestingPerSide);
    }
    ASSERT_EQ(summary->phase_events[phase_index], orders_per_phase);
    summary->phase_record_checksums[phase_index] =
        summary->serialized_records.value;
    summary->phase_state_checksums[phase_index] =
        core.deterministic_state_checksum();
  }

  ASSERT_EQ(serial, kStagedPhaseCount * orders_per_phase);
  ASSERT_EQ(summary->commands, 1 + kStagedPhaseCount * orders_per_phase);
  ASSERT_EQ(summary->trades, 0U);
  ASSERT_EQ(summary->expected_events.value, summary->observed_events.value);
  summary->elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start)
                            .count();
}

void expect_staged_replay_equal(const StagedSummary& first,
                                const StagedSummary& replay) {
  EXPECT_EQ(first.expected_events.value, replay.expected_events.value);
  EXPECT_EQ(first.observed_events.value, replay.observed_events.value);
  EXPECT_EQ(first.serialized_records.value, replay.serialized_records.value);
  EXPECT_EQ(first.phase_events, replay.phase_events);
  EXPECT_EQ(first.phase_record_checksums, replay.phase_record_checksums);
  EXPECT_EQ(first.phase_state_checksums, replay.phase_state_checksums);
  EXPECT_EQ(first.commands, replay.commands);
  EXPECT_EQ(first.trades, replay.trades);
}

TEST(MatchingCapacityEnvelopeTest, RefillsOneBookAfter4096PerSideCancellation) {
  StagedSummary first;
  ASSERT_NO_FATAL_FAILURE(run_staged_envelope(1, &first));
  StagedSummary replay;
  ASSERT_NO_FATAL_FAILURE(run_staged_envelope(1, &replay));
  EXPECT_EQ(first.phase_events[0], 2 * kHighRestingPerSide);
  EXPECT_EQ(first.phase_events[1], 2 * kHighRestingPerSide);
  EXPECT_EQ(first.phase_events[2], 2 * kHighRestingPerSide);
  EXPECT_EQ(first.commands, 1 + 6 * kHighRestingPerSide);
  EXPECT_EQ(first.trades, 0U);
  EXPECT_EQ(first.expected_events.value, first.observed_events.value);
  expect_staged_replay_equal(first, replay);
  std::cout << "core_only_scaled_4096x3: first_ms=" << first.elapsed_ms
            << " replay_ms=" << replay.elapsed_ms
            << " commands=" << first.commands
            << " events_checksum=" << first.serialized_records.value
            << " final_state_checksum=" << first.phase_state_checksums[2]
            << '\n';
}

// Explicit manual-only high profile. Both --gtest_also_run_disabled_tests and
// the exact environment acknowledgement are needed to run 3,686,400 commands.
TEST(MatchingCapacityEnvelopeTest, DISABLED_StagedHigh150Books4096PerSide) {
  const char* approval = std::getenv("SIMPLEMATCH_HIGH_ENVELOPE_APPROVAL");
  if (approval == nullptr ||
      std::string_view(approval) != "RUN_3686400_CORE_COMMANDS") {
    GTEST_SKIP()
        << "Set SIMPLEMATCH_HIGH_ENVELOPE_APPROVAL=RUN_3686400_CORE_COMMANDS "
           "and --gtest_also_run_disabled_tests with an exact test filter";
  }

  StagedSummary summary;
  ASSERT_NO_FATAL_FAILURE(run_staged_envelope(kInstrumentCount, &summary));
  constexpr std::size_t kHighOrdersPerPhase =
      kInstrumentCount * 2 * kHighRestingPerSide;
  for (const std::size_t count : summary.phase_events) {
    EXPECT_EQ(count, kHighOrdersPerPhase);
  }
  EXPECT_EQ(summary.commands, 1 + 3 * kHighOrdersPerPhase);
  EXPECT_EQ(summary.trades, 0U);
  EXPECT_EQ(summary.expected_events.value, summary.observed_events.value);
  std::cout << "core_only_HIGH_4096x3: elapsed_ms=" << summary.elapsed_ms
            << " commands=" << summary.commands
            << " events_checksum=" << summary.serialized_records.value
            << " fill_state_checksum=" << summary.phase_state_checksums[0]
            << " cleared_state_checksum=" << summary.phase_state_checksums[1]
            << " refill_state_checksum=" << summary.phase_state_checksums[2]
            << '\n';
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
