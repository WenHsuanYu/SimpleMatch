#include "simplematch/matching/runtime/input_offset_ledger.hpp"

#include <limits>
#include <stdexcept>

namespace simplematch::matching {

InputOffsetLedger::InputOffsetLedger(std::size_t capacity) : entries_(capacity) {
  if (capacity == 0) {
    throw std::invalid_argument("input offset ledger capacity must be positive");
  }
}

InputLedgerResult InputOffsetLedger::append(
    InputSequence sequence, std::int64_t kafka_offset) {
  if (pending_count_ == entries_.size()) {
    return InputLedgerResult::kBackpressured;
  }
  if (sequence < next_sequence_) {
    return InputLedgerResult::kDuplicate;
  }
  if (sequence > next_sequence_) {
    return InputLedgerResult::kSequenceOutOfOrder;
  }
  if (kafka_offset < 0 ||
      (next_kafka_offset_.has_value() && kafka_offset != *next_kafka_offset_)) {
    return InputLedgerResult::kOffsetOutOfOrder;
  }
  if (sequence == std::numeric_limits<InputSequence>::max() ||
      kafka_offset == std::numeric_limits<std::int64_t>::max()) {
    return InputLedgerResult::kSequenceOutOfOrder;
  }

  const std::size_t index = (head_index_ + pending_count_) % entries_.size();
  Entry &entry = entries_[index];
  entry = Entry{sequence, kafka_offset, false};
  ++pending_count_;
  ++next_sequence_;
  next_kafka_offset_ = kafka_offset + 1;
  return InputLedgerResult::kAccepted;
}

InputLedgerResult InputOffsetLedger::complete(InputSequence sequence) {
  Entry *entry = find(sequence);
  if (entry == nullptr) {
    if (highest_completed_sequence_.has_value() && sequence <= *highest_completed_sequence_) {
      return InputLedgerResult::kDuplicate;
    }
    return InputLedgerResult::kUnknownSequence;
  }
  if (entry->completed) {
    return InputLedgerResult::kDuplicate;
  }
  entry->completed = true;
  advance_contiguous_completion();
  return InputLedgerResult::kAccepted;
}

std::optional<std::int64_t> InputOffsetLedger::next_commit_offset() const {
  if (!highest_completed_offset_.has_value()) {
    return std::nullopt;
  }
  const auto next_offset = *highest_completed_offset_ + 1;
  if (committed_next_offset_.has_value() && next_offset <= *committed_next_offset_) {
    return std::nullopt;
  }
  return next_offset;
}

bool InputOffsetLedger::acknowledge_commit(std::int64_t next_offset) {
  const auto expected = next_commit_offset();
  if (!expected.has_value() || *expected != next_offset) {
    return false;
  }
  committed_next_offset_ = next_offset;
  return true;
}

std::optional<InputSequence> InputOffsetLedger::highest_contiguous_completed_sequence() const {
  return highest_completed_sequence_;
}

std::optional<std::int64_t> InputOffsetLedger::highest_contiguous_completed_offset() const {
  return highest_completed_offset_;
}

std::size_t InputOffsetLedger::pending_count() const noexcept {
  return pending_count_;
}

std::size_t InputOffsetLedger::capacity() const noexcept {
  return entries_.size();
}

void InputOffsetLedger::advance_contiguous_completion() {
  while (pending_count_ != 0 && entries_[head_index_].completed) {
    const Entry &entry = entries_[head_index_];
    highest_completed_sequence_ = entry.sequence;
    highest_completed_offset_ = entry.kafka_offset;
    entries_[head_index_] = Entry{};
    head_index_ = (head_index_ + 1) % entries_.size();
    --pending_count_;
  }
}

const InputOffsetLedger::Entry *InputOffsetLedger::find(InputSequence sequence) const {
  for (std::size_t position = 0; position < pending_count_; ++position) {
    const Entry &entry = entries_[(head_index_ + position) % entries_.size()];
    if (entry.sequence == sequence) {
      return &entry;
    }
  }
  return nullptr;
}

InputOffsetLedger::Entry *InputOffsetLedger::find(InputSequence sequence) {
  for (std::size_t position = 0; position < pending_count_; ++position) {
    Entry &entry = entries_[(head_index_ + position) % entries_.size()];
    if (entry.sequence == sequence) {
      return &entry;
    }
  }
  return nullptr;
}

} // namespace simplematch::matching
