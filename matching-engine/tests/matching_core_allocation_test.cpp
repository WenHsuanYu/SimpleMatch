#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <new>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "matching_test_support.hpp"
#include "simplematch/matching/core/deterministic_matching_core.hpp"

#ifdef _MSC_VER
#include <malloc.h>
#endif

#include <gtest/gtest.h>

namespace {

std::atomic<bool> count_allocations{false};
std::atomic<std::size_t> allocation_count{0};

void record_allocation() {
  if (count_allocations.load(std::memory_order_relaxed)) {
    allocation_count.fetch_add(1, std::memory_order_relaxed);
  }
}

void *allocate(std::size_t size) {
  void *pointer = std::malloc(size == 0 ? 1 : size);
  if (pointer == nullptr) {
    throw std::bad_alloc();
  }
  record_allocation();
  return pointer;
}

void *allocate_aligned(std::size_t size, std::size_t alignment) {
  if (size > std::numeric_limits<std::size_t>::max() - (alignment - 1)) {
    throw std::bad_alloc();
  }
  const std::size_t allocation_size =
      size == 0 ? alignment : ((size + alignment - 1) / alignment) * alignment;
#ifdef _MSC_VER
  void *pointer = _aligned_malloc(allocation_size, alignment);
#else
  void *pointer = std::aligned_alloc(alignment, allocation_size);
#endif
  if (pointer == nullptr) {
    throw std::bad_alloc();
  }
  record_allocation();
  return pointer;
}

void deallocate_aligned(void *pointer) noexcept {
#ifdef _MSC_VER
  _aligned_free(pointer);
#else
  std::free(pointer);
#endif
}

class AllocationCountingScope {
public:
  AllocationCountingScope() {
    allocation_count.store(0, std::memory_order_relaxed);
    count_allocations.store(true, std::memory_order_relaxed);
  }

  ~AllocationCountingScope() {
    count_allocations.store(false, std::memory_order_relaxed);
  }

  AllocationCountingScope(const AllocationCountingScope &) = delete;
  AllocationCountingScope &operator=(const AllocationCountingScope &) = delete;
  AllocationCountingScope(AllocationCountingScope&&) = delete;
  AllocationCountingScope& operator=(AllocationCountingScope&&) = delete;
};

} // namespace

// This test executable counts C++ global new allocations, not direct malloc or
// custom allocators.
void *operator new(std::size_t size) {
  return allocate(size);
}

void *operator new[](std::size_t size) {
  return allocate(size);
}

void *operator new(std::size_t size, std::align_val_t alignment) {
  return allocate_aligned(size, static_cast<std::size_t>(alignment));
}

void *operator new[](std::size_t size, std::align_val_t alignment) {
  return allocate_aligned(size, static_cast<std::size_t>(alignment));
}

void* operator new(std::size_t size,
                   const std::nothrow_t& /*unused*/) noexcept {
  try {
    return allocate(size);
  } catch (...) {
    return nullptr;
  }
}

void* operator new[](std::size_t size,
                     const std::nothrow_t& /*unused*/) noexcept {
  try {
    return allocate(size);
  } catch (...) {
    return nullptr;
  }
}

void* operator new(std::size_t size, std::align_val_t alignment,
                   const std::nothrow_t& /*unused*/) noexcept {
  try {
    return allocate_aligned(size, static_cast<std::size_t>(alignment));
  } catch (...) {
    return nullptr;
  }
}

void* operator new[](std::size_t size, std::align_val_t alignment,
                     const std::nothrow_t& /*unused*/) noexcept {
  try {
    return allocate_aligned(size, static_cast<std::size_t>(alignment));
  } catch (...) {
    return nullptr;
  }
}

void operator delete(void *pointer) noexcept {
  std::free(pointer);
}

void operator delete[](void *pointer) noexcept {
  std::free(pointer);
}

void operator delete(void* pointer, std::size_t /*unused*/) noexcept {
  std::free(pointer);
}

void operator delete[](void* pointer, std::size_t /*unused*/) noexcept {
  std::free(pointer);
}

void operator delete(void* pointer, std::align_val_t /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void* pointer, std::align_val_t /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void* pointer, std::size_t /*unused*/,
                     std::align_val_t /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void* pointer, std::size_t /*unused*/,
                       std::align_val_t /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void* pointer, std::align_val_t /*unused*/,
                     const std::nothrow_t& /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void* pointer, std::align_val_t /*unused*/,
                       const std::nothrow_t& /*unused*/) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void* pointer, const std::nothrow_t& /*unused*/) noexcept {
  std::free(pointer);
}

void operator delete[](void* pointer,
                       const std::nothrow_t& /*unused*/) noexcept {
  std::free(pointer);
}

namespace simplematch::matching {
namespace {

using test_support::context;
using test_support::uuid;

constexpr std::size_t kInstrumentCount = 150;
static_assert(kInstrumentCount <= 256);

CoreUuid uuid_for_instrument(std::string_view text,
                             std::size_t instrument_index) {
  CoreUuid value = uuid(text);
  // Keep the final byte for the command role and use the preceding byte for the
  // instrument.
  value.bytes[value.bytes.size() - 2] =
      static_cast<std::uint8_t>(instrument_index);
  return value;
}

CoreCommand order_for_book(
    std::string_view command_id, std::string_view order_id, CoreSide side,
    std::int64_t price, const CoreInstrument& book_instrument,
    std::size_t instrument_index,
    CoreTimeInForce time_in_force = CoreTimeInForce::kRod) {
  return CoreCommand::new_order(
      context(), uuid_for_instrument(command_id, instrument_index),
      uuid_for_instrument(order_id, instrument_index),
      uuid("0198a001-0000-7000-8000-0000000000aa"), book_instrument, side,
      ShareQuantity(100), FixedPrice(price), CoreOrderType::kLimit,
      time_in_force);
}

std::vector<CoreInstrument> target_instruments() {
  std::vector<CoreInstrument> instruments;
  instruments.reserve(kInstrumentCount);
  for (std::size_t index = 0; index < kInstrumentCount; ++index) {
    const auto value = CoreInstrument::create("XTAI", "B" + std::to_string(index));
    if (!value.has_value()) {
      throw std::logic_error("allocation test instrument fixture is invalid");
    }
    instruments.push_back(*value);
  }
  return instruments;
}

using RepresentativeCommands = std::array<CoreCommand, 7>;

RepresentativeCommands representative_commands(
    const CoreInstrument& book_instrument, std::size_t instrument_index) {
  return {
      order_for_book("0198a001-0000-7000-8000-000000000101",
                     "0198a001-0000-7000-8000-000000000111", CoreSide::kBuy,
                     1'000'000, book_instrument, instrument_index),
      order_for_book("0198a001-0000-7000-8000-000000000102",
                     "0198a001-0000-7000-8000-000000000112", CoreSide::kSell,
                     2'000'000, book_instrument, instrument_index),
      order_for_book("0198a001-0000-7000-8000-000000000103",
                     "0198a001-0000-7000-8000-000000000113", CoreSide::kBuy,
                     2'000'000, book_instrument, instrument_index),
      order_for_book("0198a001-0000-7000-8000-000000000104",
                     "0198a001-0000-7000-8000-000000000114", CoreSide::kBuy,
                     1'000'000, book_instrument, instrument_index,
                     CoreTimeInForce::kIoc),
      order_for_book("0198a001-0000-7000-8000-000000000105",
                     "0198a001-0000-7000-8000-000000000115", CoreSide::kBuy,
                     1'000'000, book_instrument, instrument_index,
                     CoreTimeInForce::kFok),
      CoreCommand::cancel(
          context(),
          uuid_for_instrument("0198a001-0000-7000-8000-000000000106",
                              instrument_index),
          uuid_for_instrument("0198a001-0000-7000-8000-000000000111",
                              instrument_index),
          uuid("0198a001-0000-7000-8000-0000000000aa"), book_instrument,
          CoreSide::kBuy),
      order_for_book("0198a001-0000-7000-8000-000000000107",
                     "0198a001-0000-7000-8000-000000000117", CoreSide::kBuy,
                     1'000'000, book_instrument, instrument_index)};
}

std::vector<RepresentativeCommands> representative_command_sets(
    std::span<const CoreInstrument> instruments) {
  std::vector<RepresentativeCommands> command_sets;
  command_sets.reserve(instruments.size());
  for (std::size_t index = 0; index < instruments.size(); ++index) {
    command_sets.push_back(representative_commands(instruments[index], index));
  }
  return command_sets;
}

bool run_representative_command_sets(
    DeterministicMatchingCore& engine,
    std::span<const RepresentativeCommands> command_sets,
    const CoreCommand& close) {
  for (const RepresentativeCommands& commands : command_sets) {
    for (const CoreCommand& command : commands) {
      if (engine.process(command) != MatchingProcessResult::kApplied) {
        return false;
      }
    }
  }
  if (engine.process(close) != MatchingProcessResult::kApplied) {
    return false;
  }
  const auto events = engine.events();
  return events.size() == command_sets.size() &&
         std::ranges::all_of(events, [](const CoreEvent& event) {
           return event.type == CoreEventType::kOrderExpired;
         });
}

TEST(MatchingCoreAllocationTest, CounterDetectsNormalAndAlignedAllocations) {
  constexpr std::size_t kAlignedAllocationSize = 64;
  constexpr auto kAlignment =
      static_cast<std::align_val_t>(kAlignedAllocationSize);
  {
    const AllocationCountingScope counting;
    void* const ordinary = ::operator new(1);
    void* const aligned = ::operator new(kAlignedAllocationSize, kAlignment);
    ::operator delete(ordinary);
    ::operator delete(aligned, kAlignment);
  }
  EXPECT_EQ(allocation_count.load(std::memory_order_relaxed), 2U);
}

TEST(MatchingCoreAllocationTest, CloseBarrierDoesNotAllocateAfterWarmup) {
  const auto instruments = target_instruments();
  DeterministicMatchingCore engine(instruments, 2, 0);
  for (std::size_t index = 0; index < instruments.size(); ++index) {
    const CoreInstrument& instrument = instruments[index];
    const std::array resting_orders{
        order_for_book("0198a001-0000-7000-8000-000000000001",
                       "0198a001-0000-7000-8000-000000000011", CoreSide::kBuy,
                       1'000'000, instrument, index),
        order_for_book("0198a001-0000-7000-8000-000000000002",
                       "0198a001-0000-7000-8000-000000000012", CoreSide::kBuy,
                       990'000, instrument, index),
        order_for_book("0198a001-0000-7000-8000-000000000003",
                       "0198a001-0000-7000-8000-000000000013", CoreSide::kSell,
                       2'000'000, instrument, index),
        order_for_book("0198a001-0000-7000-8000-000000000004",
                       "0198a001-0000-7000-8000-000000000014", CoreSide::kSell,
                       2'010'000, instrument, index)};
    for (const CoreCommand& order : resting_orders) {
      ASSERT_EQ(engine.process(order), MatchingProcessResult::kApplied);
    }
  }

  const CoreCommand close =
      CoreCommand::close(context(), uuid("0198a001-0000-7000-8000-000000000005"));
  auto result = MatchingProcessResult::kInvalidCommand;
  {
    const AllocationCountingScope counting;
    result = engine.process(close);
  }

  EXPECT_EQ(result, MatchingProcessResult::kApplied);
  EXPECT_EQ(engine.events().size(), 4U * kInstrumentCount);
  EXPECT_EQ(allocation_count.load(std::memory_order_relaxed), 0U);
}

TEST(MatchingCoreAllocationTest,
     RepresentativeCoreCommandsDoNotAllocateAfterInitialization) {
  const auto instruments = target_instruments();
  DeterministicMatchingCore engine(instruments, 2, 0);
  const auto command_sets = representative_command_sets(instruments);
  const auto close = CoreCommand::close(
      context(), uuid("0198a001-0000-7000-8000-0000000001ff"));

  bool completed = false;
  {
    const AllocationCountingScope counting;
    completed = run_representative_command_sets(engine, command_sets, close);
  }

  EXPECT_TRUE(completed);
  EXPECT_EQ(engine.events().size(), kInstrumentCount);
  EXPECT_EQ(allocation_count.load(std::memory_order_relaxed), 0U);
}

} // namespace
} // namespace simplematch::matching
