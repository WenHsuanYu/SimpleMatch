#include "simplematch/matching/runtime/input_offset_ledger.hpp"

#include <cstdint>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

TEST(InputOffsetLedgerTest, MapsInputSequencesAndCommitsOnlyContiguousCompletion) {
  InputOffsetLedger ledger(4);

  EXPECT_EQ(ledger.append(0, 100), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.append(1, 101), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.append(2, 102), InputLedgerResult::kAccepted);

  EXPECT_EQ(ledger.complete(2), InputLedgerResult::kAccepted);
  EXPECT_FALSE(ledger.next_commit_offset().has_value());

  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kAccepted);
  ASSERT_TRUE(ledger.next_commit_offset().has_value());
  EXPECT_EQ(*ledger.next_commit_offset(), 101);
  EXPECT_TRUE(ledger.acknowledge_commit(101));

  EXPECT_EQ(ledger.complete(1), InputLedgerResult::kAccepted);
  ASSERT_TRUE(ledger.next_commit_offset().has_value());
  EXPECT_EQ(*ledger.next_commit_offset(), 103);
  EXPECT_TRUE(ledger.acknowledge_commit(103));
  EXPECT_FALSE(ledger.next_commit_offset().has_value());
  EXPECT_EQ(ledger.pending_count(), 0);
}

TEST(InputOffsetLedgerTest, RejectsGapsAndDuplicateSequencesWithoutAdvancingTheLedger) {
  InputOffsetLedger ledger(2);

  EXPECT_EQ(ledger.append(0, 100), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.append(2, 102), InputLedgerResult::kSequenceOutOfOrder);
  EXPECT_EQ(ledger.append(0, 100), InputLedgerResult::kDuplicate);
  EXPECT_EQ(ledger.append(1, 102), InputLedgerResult::kOffsetOutOfOrder);
  EXPECT_EQ(ledger.append(1, 101), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.pending_count(), 2);
}

TEST(InputOffsetLedgerTest, AppliesBoundedBackpressureUntilCompletedPrefixIsReleased) {
  InputOffsetLedger ledger(2);

  EXPECT_EQ(ledger.append(0, 10), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.append(1, 11), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.append(2, 12), InputLedgerResult::kBackpressured);
  EXPECT_EQ(ledger.pending_count(), 2);

  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.pending_count(), 1);
  EXPECT_EQ(ledger.append(2, 12), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.pending_count(), 2);
}

TEST(InputOffsetLedgerTest, DistinguishesUnknownAndRepeatedCompletion) {
  InputOffsetLedger ledger(2);

  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kUnknownSequence);
  EXPECT_EQ(ledger.append(0, 50), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kDuplicate);
  EXPECT_EQ(ledger.complete(1), InputLedgerResult::kUnknownSequence);
}

TEST(InputOffsetLedgerTest, RequiresTheExactContiguousCommitWatermark) {
  InputOffsetLedger ledger(2);

  EXPECT_EQ(ledger.append(0, 7), InputLedgerResult::kAccepted);
  EXPECT_EQ(ledger.complete(0), InputLedgerResult::kAccepted);
  EXPECT_FALSE(ledger.acknowledge_commit(7));
  EXPECT_TRUE(ledger.acknowledge_commit(8));
  EXPECT_FALSE(ledger.acknowledge_commit(8));
}

TEST(InputOffsetLedgerTest, ReusesReleasedSlotsAcrossRepeatedWrapAround) {
  InputOffsetLedger ledger(2);

  for (InputSequence sequence = 0; sequence < 8; ++sequence) {
    const auto offset = static_cast<std::int64_t>(100 + sequence);
    ASSERT_EQ(ledger.append(sequence, offset), InputLedgerResult::kAccepted);
    ASSERT_EQ(ledger.complete(sequence), InputLedgerResult::kAccepted);
    ASSERT_TRUE(ledger.acknowledge_commit(offset + 1));
    EXPECT_EQ(ledger.pending_count(), 0U);
  }
}

} // namespace
} // namespace simplematch::matching
