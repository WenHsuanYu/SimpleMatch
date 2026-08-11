#pragma once

#include <atomic>
#include <cstddef>
#include <optional>
#include <stdexcept>
#include <utility>
#include <vector>

namespace simplematch::matching {

/** Preallocated lock-free single-producer/single-consumer ring with explicit backpressure. */
template <typename Value>
class BoundedSpscRing {
public:
  explicit BoundedSpscRing(std::size_t capacity) : slots_(capacity + 1), capacity_(capacity) {
    if (capacity == 0) {
      throw std::invalid_argument("SPSC ring capacity must be positive");
    }
  }

  [[nodiscard]] bool try_push(Value value) {
    const std::size_t write = write_index_.load(std::memory_order_relaxed);
    const std::size_t next = increment(write);
    if (next == read_index_.load(std::memory_order_acquire)) {
      return false;
    }
    slots_[write].emplace(std::move(value));
    write_index_.store(next, std::memory_order_release);
    return true;
  }

  [[nodiscard]] std::optional<Value> try_pop() {
    const std::size_t read = read_index_.load(std::memory_order_relaxed);
    if (read == write_index_.load(std::memory_order_acquire)) {
      return std::nullopt;
    }
    std::optional<Value> value = std::move(slots_[read]);
    slots_[read].reset();
    read_index_.store(increment(read), std::memory_order_release);
    return value;
  }

  [[nodiscard]] std::size_t size() const {
    const std::size_t write = write_index_.load(std::memory_order_acquire);
    const std::size_t read = read_index_.load(std::memory_order_acquire);
    return write >= read ? write - read : slots_.size() - read + write;
  }

  [[nodiscard]] std::size_t available_to_write() const {
    return capacity_ - size();
  }

private:
  [[nodiscard]] std::size_t increment(std::size_t index) const {
    return (index + 1) % slots_.size();
  }

  std::vector<std::optional<Value>> slots_;
  std::size_t capacity_;
  std::atomic<std::size_t> write_index_{};
  std::atomic<std::size_t> read_index_{};
};

} // namespace simplematch::matching
