#pragma once

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <stdexcept>
#include <stop_token>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace simplematch::matching::disruptor {

using SequenceNumber = std::int64_t;
inline constexpr SequenceNumber kInitialSequence = -1;
inline constexpr std::size_t kCacheLineBytes = 64;

// Each cursor has one writer; readers use acquire before accessing events.
class alignas(kCacheLineBytes) Sequence final {
 public:
  [[nodiscard]] SequenceNumber load() const noexcept {
    return value_.load(std::memory_order_acquire);
  }

  void store(SequenceNumber value) noexcept {
    value_.store(value, std::memory_order_release);
  }

 private:
  std::atomic<SequenceNumber> value_{kInitialSequence};
};

static_assert(sizeof(Sequence) == kCacheLineBytes);

namespace detail {

inline std::size_t checked_capacity(std::size_t capacity) {
  if (capacity == 0 || (capacity & (capacity - 1)) != 0 ||
      capacity > static_cast<std::size_t>(
                     std::numeric_limits<SequenceNumber>::max())) {
    throw std::invalid_argument(
        "Disruptor ring capacity must be a positive power of two");
  }
  return capacity;
}

class GatingSequences final {
 public:
  explicit GatingSequences(std::span<const Sequence* const> sequences)
      : sequences_(sequences.begin(), sequences.end()) {
    if (sequences_.empty() || std::any_of(sequences_.begin(), sequences_.end(),
                                          [](const Sequence* sequence) {
                                            return sequence == nullptr;
                                          })) {
      throw std::invalid_argument(
          "Disruptor requires live terminal consumer sequences");
    }
  }

  [[nodiscard]] SequenceNumber minimum() const noexcept {
    SequenceNumber result = std::numeric_limits<SequenceNumber>::max();
    for (const Sequence* sequence : sequences_) {
      result = std::min(result, sequence->load());
    }
    return result;
  }

 private:
  std::vector<const Sequence*> sequences_;
};

// Event-driven wakeup for blocking consumers. Busy-spin/yield consumers do not
// register as waiters; producers pay only one atomic load when no one blocks.
class PublicationSignal final {
 public:
  void notify() const noexcept {
    if (waiters_.load(std::memory_order_acquire) == 0) {
      return;
    }
    // Notify without locking the producer: the timed wait bounds a missed
    // notification during waiter registration.
    condition_.notify_all();
  }

  template <typename Predicate>
  void wait(std::stop_token stop, Predicate&& ready) const {
    std::unique_lock lock(mutex_);
    waiters_.fetch_add(1, std::memory_order_acq_rel);
    // A bounded fallback also covers progress of dependent consumers that
    // does not result in an additional producer notification.
    condition_.wait_for(lock, stop, std::chrono::milliseconds(1),
                        std::forward<Predicate>(ready));
    waiters_.fetch_sub(1, std::memory_order_acq_rel);
  }

 private:
  mutable std::mutex mutex_;
  mutable std::condition_variable_any condition_;
  mutable std::atomic<std::size_t> waiters_{};
};

// Difference remains correct for initial -1 without signed overflow.
inline bool has_room(SequenceNumber sequence, SequenceNumber minimum,
                     std::size_t capacity) noexcept {
  return static_cast<std::uint64_t>(sequence) -
             static_cast<std::uint64_t>(minimum) <=
         capacity;
}

inline std::size_t remaining_capacity(SequenceNumber claimed,
                                      SequenceNumber minimum,
                                      std::size_t capacity) noexcept {
  if (claimed == std::numeric_limits<SequenceNumber>::max()) {
    return 0;
  }
  const auto outstanding =
      static_cast<std::uint64_t>(claimed) - static_cast<std::uint64_t>(minimum);
  if (outstanding >= capacity) {
    return 0;
  }
  // The final sequence ID also bounds capacity. Unsigned subtraction handles
  // the initial -1 cursor without signed overflow.
  const auto remaining_numbers =
      static_cast<std::uint64_t>(std::numeric_limits<SequenceNumber>::max()) -
      static_cast<std::uint64_t>(claimed);
  return static_cast<std::size_t>(std::min(
      static_cast<std::uint64_t>(capacity -
                                 static_cast<std::size_t>(outstanding)),
      remaining_numbers));
}

}  // namespace detail

// All calls to try_claim/publish must come from the same producer thread.
class SingleProducerSequencer final {
 public:
  SingleProducerSequencer(std::size_t capacity,
                          std::span<const Sequence* const> gating)
      : capacity_(detail::checked_capacity(capacity)), gating_(gating) {}

  // Only the owning producer may use this preflight. Once it sees enough
  // space, the sole producer can publish that many slots without contention.
  [[nodiscard]] std::size_t available_to_write() const noexcept {
    if (cursor_.load() != next_) {
      return 0;
    }
    return detail::remaining_capacity(next_, gating_.minimum(), capacity_);
  }

  [[nodiscard]] std::optional<SequenceNumber> try_claim() noexcept {
    // One outstanding claim at a time: the single producer must publish in
    // sequence order even if a claim is abandoned during stack unwinding.
    if (cursor_.load() != next_) {
      return std::nullopt;
    }
    if (next_ == std::numeric_limits<SequenceNumber>::max()) {
      return std::nullopt;
    }
    const SequenceNumber candidate = next_ + 1;
    if (!detail::has_room(candidate, gating_.minimum(), capacity_)) {
      return std::nullopt;
    }
    next_ = candidate;
    return candidate;
  }

  void publish(SequenceNumber sequence) noexcept {
    assert(cursor_.load() < std::numeric_limits<SequenceNumber>::max() &&
           sequence == cursor_.load() + 1);
    cursor_.store(sequence);
    signal_.notify();
  }

  template <typename Predicate>
  void wait_for_change(std::stop_token stop, Predicate&& ready) const {
    signal_.wait(stop, std::forward<Predicate>(ready));
  }

  void signal_waiters() const noexcept { signal_.notify(); }

  [[nodiscard]] SequenceNumber highest_contiguous_published(
      SequenceNumber /*minimum*/) const noexcept {
    return cursor_.load();
  }

 private:
  std::size_t capacity_;
  detail::GatingSequences gating_;
  Sequence cursor_;
  detail::PublicationSignal signal_;
  SequenceNumber next_{kInitialSequence};
};

// Producers may claim in parallel. A slot's absolute sequence is published
// with release only after its event has been constructed.
class MultiProducerSequencer final {
 public:
  MultiProducerSequencer(std::size_t capacity,
                         std::span<const Sequence* const> gating)
      : capacity_(detail::checked_capacity(capacity)),
        mask_(capacity_ - 1),
        gating_(gating),
        availability_(
            std::make_unique<std::atomic<SequenceNumber>[]>(capacity_)) {
    for (std::size_t index = 0; index < capacity_; ++index) {
      availability_[index].store(kInitialSequence, std::memory_order_relaxed);
    }
  }

  // This is advisory under concurrent claims. It does not reserve capacity.
  [[nodiscard]] std::size_t available_to_write() const noexcept {
    return detail::remaining_capacity(claimed_.load(std::memory_order_acquire),
                                      gating_.minimum(), capacity_);
  }

  [[nodiscard]] std::optional<SequenceNumber> try_claim() noexcept {
    auto observed = claimed_.load(std::memory_order_relaxed);
    while (observed != std::numeric_limits<SequenceNumber>::max()) {
      const SequenceNumber candidate = observed + 1;
      if (!detail::has_room(candidate, gating_.minimum(), capacity_)) {
        return std::nullopt;
      }
      if (claimed_.compare_exchange_weak(observed, candidate,
                                         std::memory_order_acq_rel,
                                         std::memory_order_relaxed)) {
        return candidate;
      }
    }
    return std::nullopt;
  }

  void publish(SequenceNumber sequence) noexcept {
    availability_[static_cast<std::size_t>(sequence) & mask_].store(
        sequence, std::memory_order_release);
    signal_.notify();
  }

  template <typename Predicate>
  void wait_for_change(std::stop_token stop, Predicate&& ready) const {
    signal_.wait(stop, std::forward<Predicate>(ready));
  }

  void signal_waiters() const noexcept { signal_.notify(); }

  [[nodiscard]] SequenceNumber highest_contiguous_published(
      SequenceNumber first) const noexcept {
    const SequenceNumber claimed = claimed_.load(std::memory_order_acquire);
    for (SequenceNumber sequence = first; sequence <= claimed; ++sequence) {
      if (availability_[static_cast<std::size_t>(sequence) & mask_].load(
              std::memory_order_acquire) != sequence) {
        return sequence - 1;
      }
      if (sequence == std::numeric_limits<SequenceNumber>::max()) {
        break;
      }
    }
    return claimed;
  }

 private:
  std::size_t capacity_;
  std::size_t mask_;
  detail::GatingSequences gating_;
  alignas(kCacheLineBytes) std::atomic<SequenceNumber> claimed_{
      kInitialSequence};
  std::unique_ptr<std::atomic<SequenceNumber>[]> availability_;
  detail::PublicationSignal signal_;
};

// Fixed-storage ring. A move-only RAII claim publishes a poison slot on abort,
// preventing an incomplete producer from silently wedging the entire stream.
template <typename T, typename Sequencer>
class RingBuffer final {
 public:
  static_assert(std::is_nothrow_move_constructible_v<T> &&
                std::is_nothrow_destructible_v<T>);

  class Claim final {
   public:
    Claim(const Claim&) = delete;
    Claim& operator=(const Claim&) = delete;
    Claim& operator=(Claim&&) = delete;

    Claim(Claim&& other) noexcept
        : ring_(std::exchange(other.ring_, nullptr)),
          sequence_(other.sequence_) {}

    ~Claim() {
      if (ring_ != nullptr) {
        ring_->abandon(sequence_);
      }
    }

    [[nodiscard]] SequenceNumber sequence() const noexcept { return sequence_; }

    void publish(T value) noexcept {
      assert(ring_ != nullptr);
      ring_->publish_value(sequence_, std::move(value));
      ring_ = nullptr;
    }

   private:
    friend class RingBuffer;

    Claim(RingBuffer& ring, SequenceNumber sequence) noexcept
        : ring_(&ring), sequence_(sequence) {}

    RingBuffer* ring_;
    SequenceNumber sequence_;
  };

  RingBuffer(std::size_t capacity, std::span<const Sequence* const> gating)
      : sequencer_(capacity, gating), slots_(capacity), mask_(capacity - 1) {}

  [[nodiscard]] bool try_publish(T value) noexcept {
    auto claimed = try_claim();
    if (!claimed) {
      return false;
    }
    claimed->publish(std::move(value));
    return true;
  }

  [[nodiscard]] std::size_t available_to_write() const noexcept {
    return sequencer_.available_to_write();
  }

  [[nodiscard]] std::optional<Claim> try_claim() noexcept {
    const auto sequence = sequencer_.try_claim();
    return sequence ? std::optional<Claim>{Claim{*this, *sequence}}
                    : std::nullopt;
  }

  // Callers must prove publication and retain a gating reference while reading.
  [[nodiscard]] bool has_event(SequenceNumber sequence) const noexcept {
    if (sequence < 0) {
      return false;
    }
    return slots_[static_cast<std::size_t>(sequence) & mask_].has_value();
  }

  [[nodiscard]] const T& at(SequenceNumber sequence) const {
    if (sequence < 0) {
      throw std::out_of_range("Disruptor sequence must be nonnegative");
    }
    const auto& slot = slots_[static_cast<std::size_t>(sequence) & mask_];
    if (!slot.has_value()) {
      throw std::logic_error("Disruptor slot is abandoned or not published");
    }
    return *slot;
  }

  [[nodiscard]] const Sequencer& sequencer() const noexcept {
    return sequencer_;
  }

 private:
  void publish_value(SequenceNumber sequence, T value) noexcept {
    slots_[static_cast<std::size_t>(sequence) & mask_].emplace(
        std::move(value));
    sequencer_.publish(sequence);
  }

  void abandon(SequenceNumber sequence) noexcept {
    slots_[static_cast<std::size_t>(sequence) & mask_].reset();
    sequencer_.publish(sequence);
  }

  Sequencer sequencer_;
  std::vector<std::optional<T>> slots_;
  std::size_t mask_;
};

enum class WaitStrategy { kBusySpin, kYield, kBlocking };

// Share one failure instance across barriers and processors in a dependency
// graph. The first failure wins. Consumers check before and after handlers,
// but failure and acknowledgement are not one atomic transaction.
class PipelineFailure final {
 public:
  void fail(std::exception_ptr reason) {
    std::scoped_lock lock(mutex_);
    if (failed_.load(std::memory_order_relaxed)) {
      return;
    }
    reason_ = std::move(reason);
    failed_.store(true, std::memory_order_release);
  }

  [[nodiscard]] bool failed() const noexcept {
    return failed_.load(std::memory_order_acquire);
  }

  [[nodiscard]] std::exception_ptr reason() const {
    std::scoped_lock lock(mutex_);
    return reason_;
  }

 private:
  mutable std::mutex mutex_;
  std::exception_ptr reason_;
  std::atomic<bool> failed_{};
};

// The dependent processors, if any, must all advance past a sequence before
// that sequence can become visible to this barrier. Dependencies are immutable
// after startup and must outlive this barrier.
template <typename Sequencer>
class SequenceBarrier final {
 public:
  SequenceBarrier(const Sequencer& sequencer,
                  std::span<const Sequence* const> dependencies,
                  const PipelineFailure* pipeline_failure = nullptr)
      : sequencer_(sequencer),
        dependencies_(dependencies.begin(), dependencies.end()),
        pipeline_failure_(pipeline_failure) {
    if (std::any_of(
            dependencies_.begin(), dependencies_.end(),
            [](const Sequence* sequence) { return sequence == nullptr; })) {
      throw std::invalid_argument(
          "Disruptor barrier cannot depend on a null Sequence");
    }
  }

  [[nodiscard]] SequenceNumber available_after(
      SequenceNumber next) const noexcept {
    SequenceNumber available = sequencer_.highest_contiguous_published(next);
    for (const Sequence* dependency : dependencies_) {
      available = std::min(available, dependency->load());
    }
    return available;
  }

  // A bounded blocking wait is intentionally off the hot path. The polling
  // fallback also wakes when a predecessor progresses without publishing.
  [[nodiscard]] std::optional<SequenceNumber> wait_for(
      SequenceNumber next, WaitStrategy strategy,
      std::stop_token stop = {}) const {
    while (!alerted_.load(std::memory_order_acquire) && !failed_pipeline() &&
           !stop.stop_requested()) {
      const SequenceNumber available = available_after(next);
      if (available >= next) {
        return available;
      }
      switch (strategy) {
        case WaitStrategy::kBusySpin:
          break;
        case WaitStrategy::kYield:
          std::this_thread::yield();
          break;
        case WaitStrategy::kBlocking:
          sequencer_.wait_for_change(stop, [this, next, &stop] {
            return alerted() || stop.stop_requested() ||
                   available_after(next) >= next;
          });
          break;
      }
    }
    return std::nullopt;
  }

  void alert() noexcept {
    alerted_.store(true, std::memory_order_release);
    sequencer_.signal_waiters();
  }
  void clear_alert() noexcept {
    alerted_.store(false, std::memory_order_release);
  }

  [[nodiscard]] bool alerted() const noexcept {
    return alerted_.load(std::memory_order_acquire) || failed_pipeline();
  }

 private:
  [[nodiscard]] bool failed_pipeline() const noexcept {
    return pipeline_failure_ != nullptr && pipeline_failure_->failed();
  }

  const Sequencer& sequencer_;
  std::vector<const Sequence*> dependencies_;
  const PipelineFailure* pipeline_failure_;
  std::atomic<bool> alerted_{};
};

// The event handler, barrier and cursor are owned by one consumer thread.
// A throwing handler fails closed: its event is not acknowledged to producers.
template <typename T, typename Sequencer, typename Handler>
class BatchEventProcessor final {
 public:
  BatchEventProcessor(const RingBuffer<T, Sequencer>& ring,
                      SequenceBarrier<Sequencer>& barrier,
                      Sequence& consumer_sequence, Handler handler,
                      PipelineFailure* pipeline_failure = nullptr)
      : ring_(ring),
        barrier_(barrier),
        consumed_(consumer_sequence),
        handler_(std::move(handler)),
        pipeline_failure_(pipeline_failure) {}

  [[nodiscard]] std::size_t poll(std::size_t maximum_batch) {
    if (failed_.load(std::memory_order_acquire) || barrier_.alerted() ||
        maximum_batch == 0) {
      return 0;
    }
    const SequenceNumber previous = consumed_.load();
    if (previous == std::numeric_limits<SequenceNumber>::max()) {
      return 0;
    }
    const SequenceNumber next = previous + 1;
    const SequenceNumber available = barrier_.available_after(next);
    if (available < next) {
      return 0;
    }
    const auto count = std::min(static_cast<std::uint64_t>(available) -
                                    static_cast<std::uint64_t>(next) + 1,
                                static_cast<std::uint64_t>(maximum_batch));
    std::size_t processed = 0;
    for (std::uint64_t offset = 0; offset < count; ++offset) {
      if (barrier_.alerted()) {
        break;
      }
      const auto sequence = next + static_cast<SequenceNumber>(offset);
      if (!ring_.has_event(sequence)) {
        try {
          throw std::runtime_error(
              "Disruptor producer abandoned a claimed slot");
        } catch (...) {
          // Even an allocation failure while constructing the diagnostic must
          // record a terminal pipeline failure instead of escaping poll().
          report_failure(std::current_exception());
        }
        break;
      }
      try {
        std::invoke(handler_, ring_.at(sequence), sequence);
      } catch (...) {
        report_failure(std::current_exception());
        break;
      }
      // A predecessor can fail while this handler is running. The handler's
      // return is not permission to acknowledge after the pipeline halted.
      if (barrier_.alerted()) {
        break;
      }
      consumed_.store(sequence);
      ++processed;
    }
    return processed;
  }

  void run(std::stop_token stop, WaitStrategy wait_strategy,
           std::size_t maximum_batch = 256) {
    if (maximum_batch == 0) {
      throw std::invalid_argument(
          "Disruptor processor requires a positive batch bound");
    }
    while (!stop.stop_requested() && !failed() && !barrier_.alerted()) {
      if (poll(maximum_batch) != 0) {
        continue;
      }
      const SequenceNumber previous = consumed_.load();
      if (previous == std::numeric_limits<SequenceNumber>::max() ||
          !barrier_.wait_for(previous + 1, wait_strategy, stop)) {
        return;
      }
    }
  }

  [[nodiscard]] bool failed() const noexcept {
    return failed_.load(std::memory_order_acquire);
  }

  [[nodiscard]] std::exception_ptr failure() const noexcept {
    return failed() ? failure_ : nullptr;
  }

 private:
  void report_failure(std::exception_ptr reason) {
    failure_ = reason;
    if (pipeline_failure_ != nullptr) {
      pipeline_failure_->fail(reason);
    }
    failed_.store(true, std::memory_order_release);
    barrier_.alert();
  }

  const RingBuffer<T, Sequencer>& ring_;
  SequenceBarrier<Sequencer>& barrier_;
  Sequence& consumed_;
  Handler handler_;
  PipelineFailure* pipeline_failure_;
  std::atomic<bool> failed_{};
  std::exception_ptr failure_;
};

}  // namespace simplematch::matching::disruptor
