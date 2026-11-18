#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "simplematch/matching/runtime/matching_runtime.hpp"
#include "simplematch/matching/runtime/partition_ownership_permit.hpp"
#include "simplematch/matching/runtime/partition_replay_coordinator.hpp"

#ifdef __linux__
#include <sys/resource.h>
#endif

#include <gtest/gtest-spi.h>
#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr std::uint64_t kKiB = 1024;
constexpr std::uint64_t kMiB = kKiB * kKiB;
constexpr std::uint64_t kTwoGiB = 2 * kMiB * kKiB;
constexpr std::size_t kFullBooks = 150;
constexpr std::size_t kFullRestingPerSide = 4096;
constexpr std::size_t kFullInputSlots = 1024;
constexpr std::size_t kFullOutputSlots = 2'097'152;
constexpr std::size_t kFullMaximumPending = 1'500'000;
constexpr std::size_t kFullMaximumDistinct = 4'000'000;
constexpr std::size_t kFullCoreEvents =
    kFullBooks * 2 * kFullRestingPerSide + 1;
constexpr std::size_t kSmallRestingPerSide = 8;
constexpr std::size_t kSmallInputSlots = 8;
constexpr std::size_t kSmallOutputSlots = 32;
constexpr std::size_t kSmallMaximumPending = 32;
constexpr std::size_t kSmallMaximumDistinct = 128;
constexpr std::string_view kArtifactIdentity =
    "2026-08-11:"
    "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943";
constexpr std::string_view kImageDigest =
    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

// The coordinator's private CommandIdentity consists of a 16-byte UUID. This
// proxy has the same public key/value shapes, but does not expose its map node
// size, allocator metadata, or the cost of any pending publications.
using FingerprintMapProxy =
    std::map<std::array<std::uint8_t, 16>, std::array<char, 65>>;

struct MemorySample {
  std::uint64_t vm_rss_kib{};
  std::uint64_t vm_hwm_kib{};
  std::uint64_t vm_size_kib{};
  std::uint64_t ru_maxrss_kib{};
};

std::optional<MemorySample> memory_sample() {
#ifdef __linux__
  std::ifstream status("/proc/self/status");
  if (!status) {
    return std::nullopt;
  }
  MemorySample result;
  bool saw_rss = false;
  bool saw_hwm = false;
  bool saw_size = false;
  std::string line;
  while (std::getline(status, line)) {
    const auto read_field = [&](std::string_view name,
                                std::uint64_t* destination, bool* found) {
      if (!line.starts_with(name)) {
        return;
      }
      std::istringstream input(line.substr(name.size()));
      *found = static_cast<bool>(input >> *destination);
    };
    read_field("VmRSS:", &result.vm_rss_kib, &saw_rss);
    read_field("VmHWM:", &result.vm_hwm_kib, &saw_hwm);
    read_field("VmSize:", &result.vm_size_kib, &saw_size);
  }
  struct rusage usage{};
  if (!saw_rss || !saw_hwm || !saw_size ||
      getrusage(RUSAGE_SELF, &usage) != 0) {
    return std::nullopt;
  }
  result.ru_maxrss_kib = static_cast<std::uint64_t>(usage.ru_maxrss);
  return result;
#else
  return std::nullopt;
#endif
}

void print_sample(std::string_view phase, const MemorySample& before,
                  const MemorySample& after) {
  const auto rss_delta = static_cast<std::int64_t>(after.vm_rss_kib) -
                         static_cast<std::int64_t>(before.vm_rss_kib);
  const auto virtual_delta = static_cast<std::int64_t>(after.vm_size_kib) -
                             static_cast<std::int64_t>(before.vm_size_kib);
  std::cout << "MEMORY_PHASE " << phase << " rss_kib=" << after.vm_rss_kib
            << " rss_delta_kib=" << rss_delta
            << " vm_size_kib=" << after.vm_size_kib
            << " vm_size_delta_kib=" << virtual_delta
            << " vm_hwm_kib=" << after.vm_hwm_kib
            << " ru_maxrss_kib=" << after.ru_maxrss_kib << '\n';
}

#ifdef __linux__
// Restores the process-wide soft limit even when a fatal GTest assertion
// returns early from a profiling test. Never changes the inherited hard limit.
class ScopedAddressSpaceLimit final {
 public:
  explicit ScopedAddressSpaceLimit(const struct rlimit& original)
      : original_(original) {}

  ScopedAddressSpaceLimit(const ScopedAddressSpaceLimit&) = delete;
  ScopedAddressSpaceLimit& operator=(const ScopedAddressSpaceLimit&) = delete;

  ~ScopedAddressSpaceLimit() {
    if (applied_ && setrlimit(RLIMIT_AS, &original_) != 0) {
      ADD_FAILURE() << "Cannot restore RLIMIT_AS soft limit; errno=" << errno;
    }
  }

  [[nodiscard]] bool apply(rlim_t requested_soft_limit,
                           std::uint64_t current_virtual_bytes) {
    const rlim_t effective_limit =
        original_.rlim_cur == RLIM_INFINITY
            ? requested_soft_limit
            : std::min(original_.rlim_cur, requested_soft_limit);
    if (effective_limit <= current_virtual_bytes) {
      return false;
    }
    const struct rlimit bounded{effective_limit, original_.rlim_max};
    if (setrlimit(RLIMIT_AS, &bounded) != 0) {
      return false;
    }
    applied_ = true;
    return true;
  }

 private:
  struct rlimit original_{};
  bool applied_ = false;
};

void exit_early_with_scoped_address_space_limit() {
  struct rlimit original{};
  ASSERT_EQ(getrlimit(RLIMIT_AS, &original), 0);
  const auto baseline = memory_sample();
  ASSERT_TRUE(baseline.has_value());
  ScopedAddressSpaceLimit limit(original);
  ASSERT_TRUE(limit.apply(
      static_cast<rlim_t>(baseline->vm_size_kib * kKiB + 128 * kMiB),
      baseline->vm_size_kib * kKiB));
  FAIL() << "intentional fatal assertion inside resource limit scope";
}
#endif

std::vector<CoreInstrument> instruments(std::size_t count) {
  std::vector<CoreInstrument> result;
  result.reserve(count);
  for (std::size_t index = 0; index < count; ++index) {
    const auto instrument =
        CoreInstrument::create("XTAI", "M" + std::to_string(index));
    if (!instrument.has_value()) {
      throw std::logic_error("invalid memory profile instrument fixture");
    }
    result.push_back(*instrument);
  }
  return result;
}

std::unique_ptr<DeterministicMatchingCore> make_core(
    std::size_t book_count, std::size_t resting_per_side) {
  return std::make_unique<DeterministicMatchingCore>(instruments(book_count),
                                                     resting_per_side, 0);
}

class OfflinePermit final : public PartitionOwnershipPermit {
 public:
  [[nodiscard]] std::int32_t partition_id() const override { return 0; }
  [[nodiscard]] bool allows_processing() const override { return true; }
  [[nodiscard]] PartitionOwnershipStatus status() const override {
    return {PartitionOwnershipState::kPermitted, "OFFLINE_MEMORY_PROFILE"};
  }
};

PinnedMatchingIdentity identity() {
  return {std::string(kArtifactIdentity),
          "2026-08-11-regular",
          "stable-least-loaded-v1",
          std::string(kImageDigest),
          1,
          1,
          1};
}

CoreUuid uuid(std::string_view value) {
  const auto parsed = CoreUuid::parse(value);
  if (!parsed.has_value()) {
    throw std::logic_error("invalid memory profile UUID");
  }
  return *parsed;
}

CoreCommand resting_buy() {
  const auto context = MatchingCommandContext::create(
      kArtifactIdentity, "2026-08-11-regular", "stable-least-loaded-v1", 0);
  if (!context.has_value()) {
    throw std::logic_error("invalid memory profile context");
  }
  return CoreCommand::new_order(
      *context, uuid("0198a001-0000-7000-8000-000000000011"),
      uuid("0198a001-0000-7000-8000-000000000022"),
      uuid("0198a001-0000-7000-8000-000000000033"), instruments(1).front(),
      CoreSide::kBuy, ShareQuantity(1), FixedPrice(1'000'000),
      CoreOrderType::kLimit, CoreTimeInForce::kRod);
}

void print_layout_projection() {
  const std::uint64_t ring_slots =
      kFullOutputSlots * sizeof(std::optional<RuntimeOutput>);
  const std::uint64_t core_event_reserve = kFullCoreEvents * sizeof(CoreEvent);
  const std::uint64_t proxy_map_value_lower =
      kFullMaximumDistinct * sizeof(FingerprintMapProxy::value_type);
  std::cout << "MEMORY_LAYOUT runtime_output_optional_bytes="
            << sizeof(std::optional<RuntimeOutput>)
            << " core_event_bytes=" << sizeof(CoreEvent)
            << " core_command_bytes=" << sizeof(CoreCommand)
            << " map_proxy_value_bytes="
            << sizeof(FingerprintMapProxy::value_type)
            << " ring_slot_storage_bytes=" << ring_slots
            << " core_event_reserved_bytes=" << core_event_reserve
            << " four_million_map_value_lower_bound_bytes="
            << proxy_map_value_lower
            << " fingerprint_map_nodes_at_constructor=0"
            << " pending_publications_preallocated_to_limit=false\n";
  std::cout << "MEMORY_CAVEAT "
               "map_value_lower_bound_excludes_tree_nodes_allocator_overhead"
               " ring_slots_are_constructed_but_resident_pages_must_be_measured"
               " deque_empty_constructor_may_allocate_implementation_storage"
               " event_vector_reserve_is_not_fully_resident_at_constructor\n";
}

TEST(MatchingMemoryEnvelopeTest,
     ReportsSourceBackedLayoutWithoutAllocatingFullEnvelope) {
  EXPECT_EQ(kFullCoreEvents, 1'228'801U);
  EXPECT_EQ(kFullOutputSlots, 1U << 21);
  EXPECT_GE(kFullOutputSlots, kFullCoreEvents + 1);
  EXPECT_GE(kFullMaximumPending, kFullCoreEvents);
  EXPECT_EQ(sizeof(std::array<std::uint8_t, 16>), 16U);
  EXPECT_EQ(sizeof(std::array<char, 65>), 65U);
  print_layout_projection();
}

TEST(MatchingMemoryEnvelopeTest,
     SamplesBoundedOneBookConstructorsAndRingHighWater) {
  const auto baseline = memory_sample();
  if (!baseline.has_value()) {
    GTEST_SKIP() << "Linux /proc/self/status and getrusage are required";
  }
  const auto permit = std::make_shared<OfflinePermit>();
  {
    auto core = make_core(1, kSmallRestingPerSide);
    ASSERT_EQ(core->maximum_output_events(), 17U);
    const auto after_core = memory_sample();
    ASSERT_TRUE(after_core.has_value());
    print_sample("small_core", *baseline, *after_core);

    MatchingRuntime runtime(std::move(core), kSmallInputSlots,
                            kSmallOutputSlots, permit);
    const auto after_runtime = memory_sample();
    ASSERT_TRUE(after_runtime.has_value());
    print_sample("small_runtime", *after_core, *after_runtime);

    const auto context = MatchingCommandContext::create(
        kArtifactIdentity, "2026-08-11-regular", "stable-least-loaded-v1", 0);
    ASSERT_TRUE(context.has_value());
    ASSERT_TRUE(
        runtime
            .submit(CoreCommand::open(
                *context, uuid("0198a001-0000-7000-8000-000000000001"), 15))
            .has_value());
    ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
    ASSERT_TRUE(runtime.take_output().has_value());
    ASSERT_EQ(runtime.output_size(), 0U);
    ASSERT_TRUE(runtime.submit(resting_buy()).has_value());
    ASSERT_EQ(runtime.process_one(), MatchingRuntimeStep::kProcessed);
    const auto metrics = runtime.metrics();
    EXPECT_EQ(metrics.input_capacity, kSmallInputSlots);
    EXPECT_EQ(metrics.output_capacity, kSmallOutputSlots);
    EXPECT_EQ(metrics.input_high_watermark, 1U);
    EXPECT_EQ(metrics.output_high_watermark, 2U);
    EXPECT_EQ(metrics.output_occupancy, 2U);
    std::cout << "MEMORY_HIGH_WATER input=" << metrics.input_high_watermark
              << " output=" << metrics.output_high_watermark << '\n';
  }
  const auto after_runtime_destroy = memory_sample();
  ASSERT_TRUE(after_runtime_destroy.has_value());
  print_sample("small_runtime_destroyed", *baseline, *after_runtime_destroy);

  {
    PartitionReplayCoordinator coordinator(
        {"matching.commands", 0}, identity(), permit,
        make_core(1, kSmallRestingPerSide), kSmallInputSlots, kSmallOutputSlots,
        kSmallMaximumDistinct, kSmallMaximumPending);
    EXPECT_EQ(coordinator.runtime().maximum_output_events(), 17U);
    EXPECT_EQ(coordinator.runtime().metrics().output_capacity,
              kSmallOutputSlots);
    EXPECT_EQ(coordinator.status().pending_publication_count, 0U);
    const auto after_coordinator = memory_sample();
    ASSERT_TRUE(after_coordinator.has_value());
    print_sample("small_coordinator", *after_runtime_destroy,
                 *after_coordinator);
  }
}

TEST(MatchingMemoryEnvelopeTest,
     SamplesBoundedFingerprintMapProxyWithoutFourMillionNodes) {
  const auto baseline = memory_sample();
  if (!baseline.has_value()) {
    GTEST_SKIP() << "Linux /proc/self/status and getrusage are required";
  }
  constexpr std::size_t kProxyNodes = 8192;
  FingerprintMapProxy fingerprints;
  for (std::uint64_t index = 0; index < kProxyNodes; ++index) {
    std::array<std::uint8_t, 16> key{};
    for (std::size_t byte = 0; byte < sizeof(index); ++byte) {
      key[byte] = static_cast<std::uint8_t>(index >> (byte * 8));
    }
    fingerprints.emplace(key, std::array<char, 65>{});
  }
  ASSERT_EQ(fingerprints.size(), kProxyNodes);
  const auto populated = memory_sample();
  ASSERT_TRUE(populated.has_value());
  print_sample("proxy_map_8192_nodes", *baseline, *populated);
  std::cout
      << "MEMORY_CAVEAT proxy_map_delta_is_not_coordinator_fingerprint_usage"
         " and_cannot_extrapolate_four_million_entries_exactly\n";
}

#ifdef __linux__
TEST(MatchingMemoryEnvelopeTest, RestoresAddressSpaceLimitAfterScope) {
  struct rlimit original{};
  ASSERT_EQ(getrlimit(RLIMIT_AS, &original), 0);
  const auto baseline = memory_sample();
  ASSERT_TRUE(baseline.has_value());
  const auto bounded_bytes = baseline->vm_size_kib * kKiB + 128 * kMiB;
  if (original.rlim_cur != RLIM_INFINITY &&
      original.rlim_cur <= bounded_bytes) {
    GTEST_SKIP() << "Process virtual-memory limit leaves no test headroom";
  }
  {
    ScopedAddressSpaceLimit limit(original);
    ASSERT_TRUE(limit.apply(static_cast<rlim_t>(bounded_bytes),
                            baseline->vm_size_kib * kKiB));
    struct rlimit during{};
    ASSERT_EQ(getrlimit(RLIMIT_AS, &during), 0);
    EXPECT_EQ(during.rlim_cur, static_cast<rlim_t>(bounded_bytes));
    EXPECT_EQ(during.rlim_max, original.rlim_max);
  }
  struct rlimit restored{};
  ASSERT_EQ(getrlimit(RLIMIT_AS, &restored), 0);
  EXPECT_EQ(restored.rlim_cur, original.rlim_cur);
  EXPECT_EQ(restored.rlim_max, original.rlim_max);
}

TEST(MatchingMemoryEnvelopeTest, RestoresAddressSpaceLimitAfterFatalAssertion) {
  struct rlimit original{};
  ASSERT_EQ(getrlimit(RLIMIT_AS, &original), 0);
  const auto baseline = memory_sample();
  ASSERT_TRUE(baseline.has_value());
  if (original.rlim_cur != RLIM_INFINITY &&
      original.rlim_cur <= baseline->vm_size_kib * kKiB + 128 * kMiB) {
    GTEST_SKIP() << "Process virtual-memory limit leaves no test headroom";
  }
  EXPECT_FATAL_FAILURE(exit_early_with_scoped_address_space_limit(),
                       "intentional fatal assertion");
  struct rlimit restored{};
  ASSERT_EQ(getrlimit(RLIMIT_AS, &restored), 0);
  EXPECT_EQ(restored.rlim_cur, original.rlim_cur);
  EXPECT_EQ(restored.rlim_max, original.rlim_max);
}
#endif

// This measures an equivalent key/value tree shape, not the private
// coordinator map or its Kafka ingestion, and never runs without two opt-ins.
TEST(MatchingMemoryEnvelopeTest,
     DISABLED_ProfilesFourMillionFingerprintMapProxyNodes) {
  const char* acknowledgement =
      std::getenv("SIMPLEMATCH_MEMORY_PROXY_APPROVAL");
  if (acknowledgement == nullptr ||
      std::string_view(acknowledgement) != "RUN_4000000_PROXY_NODES") {
    GTEST_SKIP()
        << "Set SIMPLEMATCH_MEMORY_PROXY_APPROVAL=RUN_4000000_PROXY_NODES "
           "and --gtest_also_run_disabled_tests with an exact filter";
  }
#ifdef __linux__
  const auto baseline = memory_sample();
  ASSERT_TRUE(baseline.has_value());
  struct rlimit current_limit{};
  if (getrlimit(RLIMIT_AS, &current_limit) != 0) {
    GTEST_SKIP() << "Cannot read process virtual-memory limit";
  }
  ScopedAddressSpaceLimit limit(current_limit);
  if (!limit.apply(static_cast<rlim_t>(kTwoGiB),
                   baseline->vm_size_kib * kKiB)) {
    GTEST_SKIP() << "Cannot apply the 2-GiB process virtual-memory bound";
  }

  FingerprintMapProxy fingerprints;
  for (std::uint64_t index = 0; index < kFullMaximumDistinct; ++index) {
    std::array<std::uint8_t, 16> key{};
    for (std::size_t byte = 0; byte < sizeof(index); ++byte) {
      key[byte] = static_cast<std::uint8_t>(index >> (byte * 8));
    }
    if (!fingerprints.emplace(key, std::array<char, 65>{}).second) {
      FAIL() << "Duplicate proxy key at index " << index;
    }
    if (index + 1 == 500'000 || index + 1 == kFullMaximumDistinct) {
      const auto current = memory_sample();
      ASSERT_TRUE(current.has_value());
      print_sample(
          index + 1 == 500'000 ? "proxy_map_500000" : "proxy_map_4000000",
          *baseline, *current);
    }
  }
  ASSERT_EQ(fingerprints.size(), kFullMaximumDistinct);
  std::cout << "MEMORY_CAVEAT proxy_map_is_not_the_real_coordinator_map"
               " and_excludes_pending_publications_and_Kafka\n";
#else
  GTEST_SKIP() << "This opt-in memory test requires Linux";
#endif
}

TEST(MatchingMemoryEnvelopeTest,
     FullConstructorProfileRequiresExplicitOptInAndBound) {
  const char* opt_in = std::getenv("SIMPLEMATCH_RUN_MEMORY_ENVELOPE_FULL");
  if (opt_in == nullptr || std::string_view(opt_in) != "1") {
    GTEST_SKIP() << "Set SIMPLEMATCH_RUN_MEMORY_ENVELOPE_FULL=1 to opt in";
  }
#ifdef __linux__
  const auto baseline = memory_sample();
  ASSERT_TRUE(baseline.has_value());

  // Private RestingOrder is not available to a public-API profiler. Treat
  // 256 bytes per reserved resting order as an explicit conservative model,
  // add 25% plus 256 MiB, and enforce a 2-GiB process virtual-memory cap.
  // This is a safety admission model, not a certified peak RSS bound.
  constexpr std::uint64_t kModeledOrderBytes = 256;
  const std::uint64_t modeled_bytes =
      kFullBooks * 2 * kFullRestingPerSide * kModeledOrderBytes +
      kFullCoreEvents * sizeof(CoreEvent) +
      kFullOutputSlots * sizeof(std::optional<RuntimeOutput>) +
      kFullInputSlots * sizeof(CoreCommand);
  const std::uint64_t guarded_bytes =
      modeled_bytes + (modeled_bytes / 4) + 256 * kMiB;
  std::cout << "MEMORY_ADMISSION modeled_plus_guard_bytes=" << guarded_bytes
            << " max_process_virtual_bytes=" << kTwoGiB << '\n';
  if (guarded_bytes >= kTwoGiB ||
      baseline->vm_size_kib * kKiB >= kTwoGiB - guarded_bytes) {
    GTEST_SKIP()
        << "Full constructor projection exceeds the 2-GiB virtual limit";
  }
  struct rlimit current_limit{};
  if (getrlimit(RLIMIT_AS, &current_limit) != 0) {
    GTEST_SKIP() << "Cannot read RLIMIT_AS";
  }
  ScopedAddressSpaceLimit limit(current_limit);
  if (!limit.apply(static_cast<rlim_t>(kTwoGiB),
                   baseline->vm_size_kib * kKiB)) {
    GTEST_SKIP() << "Cannot apply a safe process virtual-memory ceiling";
  }

  const auto permit = std::make_shared<OfflinePermit>();
  {
    auto core = make_core(kFullBooks, kFullRestingPerSide);
    ASSERT_EQ(core->maximum_output_events(), kFullCoreEvents);
    const auto after_core = memory_sample();
    ASSERT_TRUE(after_core.has_value());
    print_sample("full_core", *baseline, *after_core);

    MatchingRuntime runtime(std::move(core), kFullInputSlots, kFullOutputSlots,
                            permit);
    const auto after_runtime = memory_sample();
    ASSERT_TRUE(after_runtime.has_value());
    print_sample("full_runtime", *after_core, *after_runtime);
    EXPECT_EQ(runtime.metrics().output_capacity, kFullOutputSlots);
    EXPECT_EQ(runtime.maximum_output_events(), kFullCoreEvents);
  }
  const auto after_runtime_destroy = memory_sample();
  ASSERT_TRUE(after_runtime_destroy.has_value());
  print_sample("full_runtime_destroyed", *baseline, *after_runtime_destroy);

  {
    PartitionReplayCoordinator coordinator(
        {"matching.commands", 0}, identity(), permit,
        make_core(kFullBooks, kFullRestingPerSide), kFullInputSlots,
        kFullOutputSlots, kFullMaximumDistinct, kFullMaximumPending);
    EXPECT_EQ(coordinator.runtime().maximum_output_events(), kFullCoreEvents);
    EXPECT_EQ(coordinator.runtime().metrics().output_capacity,
              kFullOutputSlots);
    EXPECT_EQ(coordinator.status().pending_publication_count, 0U);
    const auto after_coordinator = memory_sample();
    ASSERT_TRUE(after_coordinator.has_value());
    print_sample("full_coordinator", *after_runtime_destroy,
                 *after_coordinator);
  }
  std::cout << "MEMORY_CAVEAT full_profile_measures_construction_only"
               " map_four_million_nodes_and_pending_1_5m_are_NOT_populated"
               " 4GiB_peak_is_NOT_certified\n";
#else
  GTEST_SKIP() << "Full memory profiling requires Linux";
#endif
}

}  // namespace
}  // namespace simplematch::matching
