#include "simplematch/matching/core/deterministic_matching_core.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

#ifdef __linux__
#include <sys/resource.h>
#endif

namespace simplematch::matching {
namespace {

constexpr std::size_t kProductionBookCount = 150;
constexpr std::size_t kDefaultWarmupIterations = 10;
constexpr std::size_t kDefaultMeasuredIterations = 100;
constexpr std::size_t kDefaultMaximumRestingOrders = 256;
constexpr std::string_view kArtifactIdentity =
    "2026-08-11:benchmark-artifact-7cd06c51691bcde248e606ed1adfaddc";
constexpr std::string_view kTradingSession = "2026-08-11-regular";
constexpr std::string_view kRoutingAlgorithm = "stable-least-loaded-v1";

struct Options {
  std::size_t warmup_iterations = kDefaultWarmupIterations;
  std::size_t measured_iterations = kDefaultMeasuredIterations;
  std::size_t maximum_resting_orders = kDefaultMaximumRestingOrders;
};

struct WorkItem {
  CoreCommand rest;
  CoreCommand cancel;
};

struct Measurements {
  std::vector<std::uint64_t> process_latencies_ns;
  std::uint64_t measured_commands{};
  std::uint64_t measured_events{};
  std::uint64_t duplicate_event_ids{};
  std::uint64_t rejected_commands{};
  std::uint64_t event_checksum = 1469598103934665603ULL;
  std::unordered_set<std::string> event_ids;
};

std::uint64_t checksum_event(std::uint64_t checksum, const CoreEvent &event) {
  const auto mix_byte = [&checksum](std::uint8_t byte) {
    checksum ^= byte;
    checksum *= 1099511628211ULL;
  };
  const auto mix_integer = [&mix_byte](std::uint64_t value) {
    for (std::size_t index = 0; index < sizeof(value); ++index) {
      mix_byte(static_cast<std::uint8_t>(value >> (index * 8)));
    }
  };
  const auto mix_text = [&mix_byte](std::string_view value) {
    for (const char character : value) {
      mix_byte(static_cast<std::uint8_t>(character));
    }
  };
  const auto mix_uuid = [&mix_byte](const CoreUuid &value) {
    for (const std::uint8_t byte : value.bytes) {
      mix_byte(byte);
    }
  };
  const auto mix_instrument = [&mix_byte, &mix_text](const CoreInstrument &value) {
    for (const char character : value.venue) {
      mix_byte(static_cast<std::uint8_t>(character));
    }
    mix_text(std::string_view(value.symbol.data(), value.symbol_length));
    mix_byte(value.symbol_length);
  };
  mix_text(std::string_view(event.event_id.data(), 64));
  mix_text(std::string_view(event.trade_id.data(), 64));
  mix_uuid(event.source_command_id);
  mix_uuid(event.order_id);
  mix_uuid(event.account_id);
  mix_uuid(event.maker_order_id);
  mix_uuid(event.maker_account_id);
  mix_uuid(event.taker_order_id);
  mix_uuid(event.taker_account_id);
  mix_instrument(event.instrument);
  mix_integer(static_cast<std::uint64_t>(event.type));
  mix_integer(static_cast<std::uint64_t>(event.side));
  mix_integer(static_cast<std::uint64_t>(event.maker_side));
  mix_integer(static_cast<std::uint64_t>(event.taker_side));
  mix_integer(static_cast<std::uint64_t>(event.quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.leaves_quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.maker_cumulative_quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.maker_leaves_quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.taker_cumulative_quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.taker_leaves_quantity.value()));
  mix_integer(static_cast<std::uint64_t>(event.maker_average_price.value()));
  mix_integer(static_cast<std::uint64_t>(event.taker_average_price.value()));
  mix_integer(static_cast<std::uint64_t>(event.price.value()));
  mix_integer(static_cast<std::uint64_t>(event.cancellation_reason));
  mix_integer(static_cast<std::uint64_t>(event.output_index));
  return checksum;
}

CoreUuid uuid(std::uint64_t sequence, std::uint8_t marker) {
  CoreUuid value{};
  value.bytes[0] = marker;
  for (std::size_t index = 0; index < sizeof(sequence); ++index) {
    value.bytes[15 - index] = static_cast<std::uint8_t>(sequence >> (index * 8));
  }
  value.bytes[6] = static_cast<std::uint8_t>((value.bytes[6] & 0x0F) | 0x70);
  value.bytes[8] = static_cast<std::uint8_t>((value.bytes[8] & 0x3F) | 0x80);
  return value;
}

std::size_t parse_positive_size(std::string_view name, std::string_view value) {
  char *end = nullptr;
  const std::string owned(value);
  const unsigned long long parsed = std::strtoull(owned.c_str(), &end, 10);
  if (end == owned.c_str() || *end != '\0' || parsed == 0 ||
      parsed > std::numeric_limits<std::size_t>::max()) {
    throw std::invalid_argument(std::string(name) + " must be a positive integer");
  }
  return static_cast<std::size_t>(parsed);
}

Options parse_options(int argc, char **argv) {
  Options options;
  for (int index = 1; index < argc; ++index) {
    const std::string_view argument(argv[index]);
    if (argument == "--help") {
      std::cout << "Usage: simplematch-matching-capacity-benchmark [--warmup N] "
                   "[--iterations N] [--maximum-resting-orders N]\n";
      std::exit(0);
    }
    if (index + 1 >= argc) {
      throw std::invalid_argument("missing value for " + std::string(argument));
    }
    const std::string_view value(argv[++index]);
    if (argument == "--warmup") {
      options.warmup_iterations = parse_positive_size(argument, value);
    } else if (argument == "--iterations") {
      options.measured_iterations = parse_positive_size(argument, value);
    } else if (argument == "--maximum-resting-orders") {
      options.maximum_resting_orders = parse_positive_size(argument, value);
    } else {
      throw std::invalid_argument("unknown argument " + std::string(argument));
    }
  }
  return options;
}

std::vector<CoreInstrument> production_instruments() {
  std::vector<CoreInstrument> instruments;
  instruments.reserve(kProductionBookCount);
  for (std::size_t index = 0; index < kProductionBookCount; ++index) {
    const auto instrument = CoreInstrument::create("XTAI", "B" + std::to_string(index));
    if (!instrument.has_value()) {
      throw std::logic_error("benchmark instrument fixture is invalid");
    }
    instruments.push_back(*instrument);
  }
  return instruments;
}

MatchingCommandContext benchmark_context() {
  const auto context = MatchingCommandContext::create(
      kArtifactIdentity, kTradingSession, kRoutingAlgorithm, 0);
  if (!context.has_value()) {
    throw std::logic_error("benchmark context fixture is invalid");
  }
  return *context;
}

std::vector<WorkItem> make_work(
    const std::vector<CoreInstrument> &instruments, const Options &options) {
  const std::size_t total_iterations = options.warmup_iterations + options.measured_iterations;
  std::vector<WorkItem> work;
  work.reserve(total_iterations * instruments.size());
  const MatchingCommandContext context = benchmark_context();
  std::uint64_t sequence = 1;
  for (std::size_t iteration = 0; iteration < total_iterations; ++iteration) {
    for (const CoreInstrument &instrument : instruments) {
      const CoreUuid command_id = uuid(sequence++, 0x01);
      const CoreUuid order_id = uuid(sequence++, 0x02);
      const CoreUuid account_id = uuid(1, 0x03);
      work.push_back({
          CoreCommand::new_order(
              context,
              command_id,
              order_id,
              account_id,
              instrument,
              CoreSide::kSell,
              ShareQuantity(100),
              FixedPrice(1'000'000),
              CoreOrderType::kLimit,
              CoreTimeInForce::kRod),
          CoreCommand::cancel(
              context, uuid(sequence++, 0x04), order_id, account_id, instrument, CoreSide::kSell)});
    }
  }
  return work;
}

std::uint64_t peak_rss_bytes() {
#ifdef __linux__
  struct rusage usage {};
  if (getrusage(RUSAGE_SELF, &usage) == 0) {
    return static_cast<std::uint64_t>(usage.ru_maxrss) * 1024;
  }
#endif
  return 0;
}

std::uint64_t percentile(std::vector<std::uint64_t> sorted, double fraction) {
  if (sorted.empty()) {
    return 0;
  }
  std::sort(sorted.begin(), sorted.end());
  const auto rank = static_cast<std::size_t>(fraction * static_cast<double>(sorted.size()));
  const std::size_t index = rank == 0 ? 0 : std::min(rank - 1, sorted.size() - 1);
  return sorted[index];
}

bool record_event(
    const CoreEvent &event,
    CoreEventType expected_type,
    const CoreUuid &command_id,
    const CoreUuid &order_id,
    bool measure,
    Measurements &measurements) {
  if (event.type != expected_type || event.source_command_id != command_id ||
      event.order_id != order_id) {
    return false;
  }
  measurements.event_checksum = checksum_event(measurements.event_checksum, event);
  if (!measure) {
    return true;
  }
  const std::string event_id(event.event_id.data(), 64);
  if (!measurements.event_ids.insert(event_id).second) {
    ++measurements.duplicate_event_ids;
  }
  ++measurements.measured_events;
  return true;
}

std::optional<std::uint64_t> replay_checksum(
    const std::vector<CoreInstrument> &instruments,
    const std::vector<WorkItem> &work,
    const Options &options) {
  DeterministicMatchingCore replay(instruments, options.maximum_resting_orders, 0);
  std::uint64_t checksum = 1469598103934665603ULL;
  for (const WorkItem &item : work) {
    if (replay.process(item.rest) != MatchingProcessResult::kApplied || replay.events().size() != 1) {
      return std::nullopt;
    }
    checksum = checksum_event(checksum, replay.events().front());
    if (replay.process(item.cancel) != MatchingProcessResult::kApplied || replay.events().size() != 1) {
      return std::nullopt;
    }
    checksum = checksum_event(checksum, replay.events().front());
  }
  return checksum;
}

bool process_one(
    DeterministicMatchingCore &core,
    const CoreCommand &command,
    CoreEventType expected_type,
    bool measure,
    Measurements &measurements) {
  const auto started = measure ? std::chrono::steady_clock::now()
                               : std::chrono::steady_clock::time_point{};
  const MatchingProcessResult result = core.process(command);
  if (measure) {
    const auto elapsed = std::chrono::steady_clock::now() - started;
    measurements.process_latencies_ns.push_back(
        static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count()));
    ++measurements.measured_commands;
  }
  if (result != MatchingProcessResult::kApplied) {
    if (measure) {
      ++measurements.rejected_commands;
    }
    return false;
  }
  if (core.events().size() != 1) {
    return false;
  }
  const CoreUuid &order_id = command.type == CoreCommandType::kNewOrder
                                 ? command.new_order_payload.order_id
                                 : command.cancel_order_payload.order_id;
  return record_event(
      core.events().front(), expected_type, command.command_id, order_id, measure, measurements);
}

void print_result(
    const Options &options,
    const Measurements &measurements,
    std::uint64_t wall_time_ns,
    std::size_t book_count,
    std::uint64_t replay_event_checksum) {
  const auto &latencies = measurements.process_latencies_ns;
  const double wall_seconds = static_cast<double>(wall_time_ns) / 1'000'000'000.0;
  const double commands_per_second =
      wall_seconds == 0.0 ? 0.0 : static_cast<double>(measurements.measured_commands) / wall_seconds;
  const double events_per_second =
      wall_seconds == 0.0 ? 0.0 : static_cast<double>(measurements.measured_events) / wall_seconds;
  const std::uint64_t expected_commands =
      static_cast<std::uint64_t>(options.measured_iterations) * book_count * 2;
  const std::uint64_t expected_events = expected_commands;
  const std::uint64_t lost_commands = expected_commands -
      std::min(expected_commands, measurements.measured_commands - measurements.rejected_commands);
  const std::uint64_t lost_events = expected_events -
      std::min(expected_events, measurements.measured_events);
  const bool checksum_matches = measurements.event_checksum == replay_event_checksum;
  std::cout << std::fixed << std::setprecision(3)
            << "{\"schema_version\":1,\"status\":\""
            << (lost_commands == 0 && lost_events == 0 && measurements.duplicate_event_ids == 0
                    && checksum_matches
                ? "PASS"
                    : "FAIL")
            << "\",\"book_count\":" << book_count
            << ",\"warmup_iterations\":" << options.warmup_iterations
            << ",\"measured_iterations\":" << options.measured_iterations
            << ",\"maximum_resting_orders_per_book\":" << options.maximum_resting_orders
            << ",\"measured_commands\":" << measurements.measured_commands
            << ",\"measured_events\":" << measurements.measured_events
            << ",\"commands_per_second\":" << commands_per_second
            << ",\"events_per_second\":" << events_per_second
            << ",\"latency_ns\":{\"p50\":" << percentile(latencies, 0.50)
            << ",\"p99\":" << percentile(latencies, 0.99)
            << ",\"p99_9\":" << percentile(latencies, 0.999)
            << ",\"max\":" << (latencies.empty() ? 0 : *std::max_element(latencies.begin(), latencies.end()))
            << "},\"rss_peak_bytes\":" << peak_rss_bytes()
            << ",\"determinism\":{\"measured_event_checksum\":\""
            << std::hex << std::setw(16) << std::setfill('0') << measurements.event_checksum
            << "\",\"replay_event_checksum\":\"" << std::setw(16) << replay_event_checksum
            << "\",\"checksum_matches\":" << std::boolalpha << checksum_matches << std::noboolalpha
            << std::dec << "}"
            << ",\"integrity\":{\"lost_commands\":" << lost_commands
            << ",\"lost_events\":" << lost_events
            << ",\"duplicate_event_ids\":" << measurements.duplicate_event_ids
            << ",\"rejected_commands\":" << measurements.rejected_commands << "}}\n";
}

} // namespace
} // namespace simplematch::matching

int main(int argc, char **argv) {
  using namespace simplematch::matching;
  try {
    const Options options = parse_options(argc, argv);
    const std::vector<CoreInstrument> instruments = production_instruments();
    const std::vector<WorkItem> work = make_work(instruments, options);
    DeterministicMatchingCore core(instruments, options.maximum_resting_orders, 0);
    Measurements measurements;
    measurements.process_latencies_ns.reserve(
        options.measured_iterations * instruments.size() * 2);
    measurements.event_ids.reserve(options.measured_iterations * instruments.size() * 2);

    const std::size_t warmup_work_items = options.warmup_iterations * instruments.size();
    for (std::size_t index = 0; index < warmup_work_items; ++index) {
      const WorkItem &item = work[index];
      if (!process_one(
              core, item.rest, CoreEventType::kOrderRested, false, measurements) ||
          !process_one(
              core, item.cancel, CoreEventType::kOrderCancelled, false, measurements)) {
        std::cerr << "benchmark integrity check failed at work item " << index << '\n';
        return 1;
      }
    }
    const auto measurement_started = std::chrono::steady_clock::now();
    for (std::size_t index = warmup_work_items; index < work.size(); ++index) {
      const WorkItem &item = work[index];
      if (!process_one(
              core, item.rest, CoreEventType::kOrderRested, true, measurements) ||
          !process_one(
              core, item.cancel, CoreEventType::kOrderCancelled, true, measurements)) {
        std::cerr << "benchmark integrity check failed at work item " << index << '\n';
        return 1;
      }
    }
    const auto wall_time_ns = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - measurement_started)
            .count());
    const std::optional<std::uint64_t> replay_event_checksum =
        replay_checksum(instruments, work, options);
    if (!replay_event_checksum.has_value()) {
      std::cerr << "deterministic replay checksum run failed\n";
      return 1;
    }
    print_result(
        options,
        measurements,
        std::max<std::uint64_t>(wall_time_ns, 1),
        instruments.size(),
        *replay_event_checksum);
    return measurements.rejected_commands == 0 && measurements.duplicate_event_ids == 0
               && measurements.event_checksum == *replay_event_checksum
           ? 0
           : 1;
  } catch (const std::exception &error) {
    std::cerr << "benchmark setup failed: " << error.what() << '\n';
    return 2;
  }
}
