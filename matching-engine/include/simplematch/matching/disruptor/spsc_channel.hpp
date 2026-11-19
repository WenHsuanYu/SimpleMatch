#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <utility>

#include "simplematch/matching/disruptor/disruptor.hpp"

namespace simplematch::matching::disruptor {

// A two-thread Disruptor graph with one producer, one terminal consumer, and
// no intermediate stage. Each channel owns the consumer gate and both cursors.
// Producer and consumer methods must each be called from their owning thread.
// The consumer moves an event before releasing the slot for reuse.
template <typename T>
class DisruptorSpscChannel final {
 public:
  explicit DisruptorSpscChannel(std::size_t capacity)
      : gates_{&consumed_}, ring_(capacity, gates_), capacity_(capacity) {}

  DisruptorSpscChannel(const DisruptorSpscChannel&) = delete;
  DisruptorSpscChannel& operator=(const DisruptorSpscChannel&) = delete;

  [[nodiscard]] bool try_push(T value) noexcept {
    return ring_.try_publish(std::move(value));
  }

  [[nodiscard]] std::optional<T> try_pop() {
    const SequenceNumber previous = consumed_.load();
    if (previous == std::numeric_limits<SequenceNumber>::max()) {
      return std::nullopt;
    }
    const SequenceNumber next = previous + 1;
    if (ring_.sequencer().highest_contiguous_published(next) < next) {
      return std::nullopt;
    }
    std::optional<T> value = ring_.take_for_consumer(next);
    consumed_.store(next);
    return value;
  }

  [[nodiscard]] std::size_t available_to_write() const noexcept {
    return ring_.available_to_write();
  }

  [[nodiscard]] std::size_t size() const noexcept {
    const SequenceNumber published =
        ring_.sequencer().highest_contiguous_published(0);
    const SequenceNumber consumed = consumed_.load();
    if (published <= consumed) {
      return 0;
    }
    return static_cast<std::size_t>(static_cast<std::uint64_t>(published) -
                                    static_cast<std::uint64_t>(consumed));
  }

  [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }

 private:
  Sequence consumed_;
  std::array<const Sequence*, 1> gates_;
  RingBuffer<T, SingleProducerSequencer> ring_;
  std::size_t capacity_;
};

}  // namespace simplematch::matching::disruptor
