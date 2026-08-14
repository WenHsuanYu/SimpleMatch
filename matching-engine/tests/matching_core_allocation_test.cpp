#include "simplematch/matching/core/deterministic_matching_core.hpp"
#include "matching_test_support.hpp"

#include <atomic>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <new>
#include <span>
#include <string>
#include <stdexcept>
#include <string_view>
#include <vector>

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
};

} // namespace

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

void *operator new(std::size_t size, const std::nothrow_t &) noexcept {
  try {
    return allocate(size);
  } catch (...) {
    return nullptr;
  }
}

void *operator new[](std::size_t size, const std::nothrow_t &) noexcept {
  try {
    return allocate(size);
  } catch (...) {
    return nullptr;
  }
}

void *operator new(
    std::size_t size, std::align_val_t alignment, const std::nothrow_t &) noexcept {
  try {
    return allocate_aligned(size, static_cast<std::size_t>(alignment));
  } catch (...) {
    return nullptr;
  }
}

void *operator new[](
    std::size_t size, std::align_val_t alignment, const std::nothrow_t &) noexcept {
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

void operator delete(void *pointer, std::size_t) noexcept {
  std::free(pointer);
}

void operator delete[](void *pointer, std::size_t) noexcept {
  std::free(pointer);
}

void operator delete(void *pointer, std::align_val_t) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void *pointer, std::align_val_t) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void *pointer, std::size_t, std::align_val_t) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void *pointer, std::size_t, std::align_val_t) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void *pointer, std::align_val_t, const std::nothrow_t &) noexcept {
  deallocate_aligned(pointer);
}

void operator delete[](void *pointer, std::align_val_t, const std::nothrow_t &) noexcept {
  deallocate_aligned(pointer);
}

void operator delete(void *pointer, const std::nothrow_t &) noexcept {
  std::free(pointer);
}

void operator delete[](void *pointer, const std::nothrow_t &) noexcept {
  std::free(pointer);
}

namespace simplematch::matching {
namespace {

using test_support::context;
using test_support::uuid;

CoreCommand order_for_book(
    std::string_view command_id,
    std::string_view order_id,
    CoreSide side,
    std::int64_t price,
    const CoreInstrument &book_instrument,
    CoreTimeInForce time_in_force = CoreTimeInForce::kRod) {
  return CoreCommand::new_order(
      context(),
      uuid(command_id),
      uuid(order_id),
      uuid("0198a001-0000-7000-8000-0000000000aa"),
      book_instrument,
      side,
      ShareQuantity(100),
      FixedPrice(price),
      CoreOrderType::kLimit,
      time_in_force);
}

std::vector<CoreInstrument> target_instruments() {
  std::vector<CoreInstrument> instruments;
  instruments.reserve(150);
  for (std::size_t index = 0; index < 150; ++index) {
    const auto value = CoreInstrument::create("XTAI", "B" + std::to_string(index));
    if (!value.has_value()) {
      throw std::logic_error("allocation test instrument fixture is invalid");
    }
    instruments.push_back(*value);
  }
  return instruments;
}

std::array<CoreCommand, 7> representative_commands(const CoreInstrument &book_instrument) {
  return {
      order_for_book(
          "0198a001-0000-7000-8000-000000000101",
          "0198a001-0000-7000-8000-000000000111",
          CoreSide::kBuy,
          1'000'000,
          book_instrument),
      order_for_book(
          "0198a001-0000-7000-8000-000000000102",
          "0198a001-0000-7000-8000-000000000112",
          CoreSide::kSell,
          2'000'000,
          book_instrument),
      order_for_book(
          "0198a001-0000-7000-8000-000000000103",
          "0198a001-0000-7000-8000-000000000113",
          CoreSide::kBuy,
          2'000'000,
          book_instrument),
      order_for_book(
          "0198a001-0000-7000-8000-000000000104",
          "0198a001-0000-7000-8000-000000000114",
          CoreSide::kBuy,
          1'000'000,
          book_instrument,
          CoreTimeInForce::kIoc),
      order_for_book(
          "0198a001-0000-7000-8000-000000000105",
          "0198a001-0000-7000-8000-000000000115",
          CoreSide::kBuy,
          1'000'000,
          book_instrument,
          CoreTimeInForce::kFok),
      CoreCommand::cancel(
          context(),
          uuid("0198a001-0000-7000-8000-000000000106"),
          uuid("0198a001-0000-7000-8000-000000000111"),
          uuid("0198a001-0000-7000-8000-0000000000aa"),
          book_instrument,
          CoreSide::kBuy),
      CoreCommand::close(context(), uuid("0198a001-0000-7000-8000-000000000107"))};
}

using RepresentativeCommands = std::array<CoreCommand, 7>;

std::vector<RepresentativeCommands> representative_command_sets(
    std::span<const CoreInstrument> instruments) {
  std::vector<RepresentativeCommands> command_sets;
  command_sets.reserve(instruments.size());
  for (const CoreInstrument &instrument : instruments) {
    command_sets.push_back(representative_commands(instrument));
  }
  return command_sets;
}

bool run_representative_commands(
    DeterministicMatchingCore &engine, std::span<const CoreCommand> commands) {
  for (const CoreCommand &command : commands) {
    if (engine.process(command) != MatchingProcessResult::kApplied) {
      return false;
    }
  }
  return true;
}

bool run_representative_command_sets(
    DeterministicMatchingCore &engine, std::span<const RepresentativeCommands> command_sets) {
  for (const RepresentativeCommands &commands : command_sets) {
    if (!run_representative_commands(engine, commands)) {
      return false;
    }
  }
  return true;
}

TEST(MatchingCoreAllocationTest, CloseBarrierDoesNotAllocateAfterWarmup) {
  const auto instruments = target_instruments();
  DeterministicMatchingCore engine(instruments, 2, 0);
  ASSERT_EQ(
      engine.process(order_for_book(
          "0198a001-0000-7000-8000-000000000001",
          "0198a001-0000-7000-8000-000000000011",
          CoreSide::kBuy,
          1'000'000,
          instruments.front())),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(order_for_book(
          "0198a001-0000-7000-8000-000000000002",
          "0198a001-0000-7000-8000-000000000012",
          CoreSide::kBuy,
          990'000,
          instruments.front())),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(order_for_book(
          "0198a001-0000-7000-8000-000000000003",
          "0198a001-0000-7000-8000-000000000013",
          CoreSide::kSell,
          2'000'000,
          instruments.front())),
      MatchingProcessResult::kApplied);
  ASSERT_EQ(
      engine.process(order_for_book(
          "0198a001-0000-7000-8000-000000000004",
          "0198a001-0000-7000-8000-000000000014",
          CoreSide::kSell,
          2'010'000,
          instruments.front())),
      MatchingProcessResult::kApplied);

  const CoreCommand close =
      CoreCommand::close(context(), uuid("0198a001-0000-7000-8000-000000000005"));
  MatchingProcessResult result;
  {
    AllocationCountingScope counting;
    result = engine.process(close);
  }

  EXPECT_EQ(result, MatchingProcessResult::kApplied);
  EXPECT_EQ(engine.events().size(), 4U);
  EXPECT_EQ(allocation_count.load(std::memory_order_relaxed), 0U);
}

TEST(MatchingCoreAllocationTest, RepresentativeCoreCommandsDoNotAllocateAfterWarmup) {
  const auto instruments = target_instruments();
  DeterministicMatchingCore engine(instruments, 2, 0);
  const auto command_sets = representative_command_sets(instruments);
  ASSERT_TRUE(run_representative_command_sets(engine, command_sets));

  bool completed;
  {
    AllocationCountingScope counting;
    completed = run_representative_command_sets(engine, command_sets);
  }

  EXPECT_TRUE(completed);
  EXPECT_EQ(allocation_count.load(std::memory_order_relaxed), 0U);
}

} // namespace
} // namespace simplematch::matching
