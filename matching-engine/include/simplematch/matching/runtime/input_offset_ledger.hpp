#pragma once

#include "simplematch/matching/runtime/input_sequence.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace simplematch::matching {

/** Result of appending or completing an input in the bounded ingress ledger. */
enum class InputLedgerResult {
  kAccepted,
  kBackpressured,
  kDuplicate,
  kSequenceOutOfOrder,
  kOffsetOutOfOrder,
  kUnknownSequence,
  kCommitMismatch
};

/**
 * Bounded process-local mapping from input sequence to Kafka offset.
 *
 * <p>The first sequence is zero and the first Kafka offset may be any non-negative partition
 * offset. Later entries must advance both coordinates by one. Completion may arrive out of order,
 * but the commit watermark advances only over a contiguous completed prefix. Completed prefix
 * entries are released from the bounded storage while their scalar watermark remains available.
 */
class InputOffsetLedger final {
public:
  explicit InputOffsetLedger(std::size_t capacity);

  /** Adds one input mapping without allocating beyond the constructor-provided capacity. */
  [[nodiscard]] InputLedgerResult append(InputSequence sequence, std::int64_t kafka_offset);

  /** Marks one input complete and advances the contiguous completion watermark when possible. */
  [[nodiscard]] InputLedgerResult complete(InputSequence sequence);

  /** Returns the next Kafka offset that may be committed, if completion advanced past commit. */
  [[nodiscard]] std::optional<std::int64_t> next_commit_offset() const;

  /** Records the exact next-offset commit acknowledged by Kafka. */
  [[nodiscard]] bool acknowledge_commit(std::int64_t next_offset);

  [[nodiscard]] std::optional<InputSequence> highest_contiguous_completed_sequence() const;
  [[nodiscard]] std::optional<std::int64_t> highest_contiguous_completed_offset() const;
  [[nodiscard]] std::size_t pending_count() const noexcept;
  [[nodiscard]] std::size_t capacity() const noexcept;

private:
  struct Entry {
    InputSequence sequence{};
    std::int64_t kafka_offset{};
    bool completed{};
  };

  void advance_contiguous_completion();
  [[nodiscard]] const Entry *find(InputSequence sequence) const;
  [[nodiscard]] Entry *find(InputSequence sequence);

  std::vector<Entry> entries_;
  std::size_t head_index_{};
  std::size_t pending_count_{};
  InputSequence next_sequence_{};
  std::optional<std::int64_t> next_kafka_offset_;
  std::optional<InputSequence> highest_completed_sequence_;
  std::optional<std::int64_t> highest_completed_offset_;
  std::optional<std::int64_t> committed_next_offset_;
};

} // namespace simplematch::matching
