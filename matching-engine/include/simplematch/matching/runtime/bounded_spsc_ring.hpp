#pragma once

#include <atomic>
#include <cstddef>
#include <functional>
#include <optional>
#include <stdexcept>
#include <utility>
#include <vector>

namespace simplematch::matching {

inline constexpr std::size_t kRuntimeCacheLineSize = 64;

/** A monotonic SPSC cursor isolated from the cursor written by the other thread. */
class alignas(kRuntimeCacheLineSize) SpscSequence final {
public:
  [[nodiscard]] std::size_t load(std::memory_order order) const noexcept {
    return value_.load(order);
  }

  void store(std::size_t value, std::memory_order order) noexcept {
    value_.store(value, order);
  }

private:
  std::atomic<std::size_t> value_{};
};

static_assert(alignof(SpscSequence) == kRuntimeCacheLineSize);
static_assert(sizeof(SpscSequence) == kRuntimeCacheLineSize);

/**
 * Power-of-two, preallocated, typed SPSC ring with explicit backpressure.
 *
 * <p>The producer constructs a slot before publishing its sequence with release ordering. The
 * consumer acquires that sequence before reading the slot and releases capacity only after the
 * value has been removed. Neither path allocates or grows the ring after construction.</p>
 */
template <typename Value>
class BoundedSpscRing final {
public:
  explicit BoundedSpscRing(std::size_t capacity)
      : slots_(capacity), capacity_(capacity), index_mask_(capacity - 1) {
    if (capacity == 0 || (capacity & (capacity - 1)) != 0) {
      throw std::invalid_argument("SPSC ring capacity must be a positive power of two");
    }
  }

  [[nodiscard]] bool try_push(Value value) {
    const std::size_t producer = producer_sequence_.load(std::memory_order_relaxed);
    const std::size_t consumer = consumer_sequence_.load(std::memory_order_acquire);
    if (producer - consumer == capacity_) {
      return false;
    }
    slots_[producer & index_mask_].emplace(std::move(value));
    producer_sequence_.store(producer + 1, std::memory_order_release);
    return true;
  }

  [[nodiscard]] std::optional<Value> try_pop() {
    const std::size_t consumer = consumer_sequence_.load(std::memory_order_relaxed);
    if (consumer == producer_sequence_.load(std::memory_order_acquire)) {
      return std::nullopt;
    }
    auto &slot = slots_[consumer & index_mask_];
    std::optional<Value> value = std::move(slot);
    slot.reset();
    consumer_sequence_.store(consumer + 1, std::memory_order_release);
    return value;
  }

  template <typename Consumer>
  std::size_t consume_batch(std::size_t maximum_items, Consumer &&consumer) {
    std::size_t consumed = 0;
    while (consumed < maximum_items) {
      auto value = try_pop();
      if (!value.has_value()) {
        break;
      }
      std::invoke(consumer, std::move(*value));
      ++consumed;
    }
    return consumed;
  }

  [[nodiscard]] std::size_t size() const {
    const std::size_t producer = producer_sequence_.load(std::memory_order_acquire);
    const std::size_t consumer = consumer_sequence_.load(std::memory_order_acquire);
    return producer - consumer;
  }

  [[nodiscard]] std::size_t available_to_write() const {
    return capacity_ - size();
  }

  [[nodiscard]] std::size_t capacity() const noexcept {
    return capacity_;
  }

private:
  std::vector<std::optional<Value>> slots_;
  std::size_t capacity_;
  std::size_t index_mask_;
  SpscSequence producer_sequence_;
  SpscSequence consumer_sequence_;
};

} // namespace simplematch::matching
