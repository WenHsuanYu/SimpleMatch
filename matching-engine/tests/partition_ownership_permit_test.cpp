#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <chrono>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

using namespace std::chrono_literals;

PartitionOwnershipIdentity expected_identity() {
  return {0, "matching-0:pod-uid-123", "2026-08-11-regular"};
}

TEST(LeaseFencedPartitionOwnershipPermitTest, SelfFencesAfterFiveSecondsOfUnconfirmedRenewal) {
  const std::chrono::steady_clock::time_point started_at{};
  LeaseFencedPartitionOwnershipPermit permit(expected_identity(), 5s);

  EXPECT_FALSE(permit.allows_processing());
  EXPECT_TRUE(permit.confirm_renewal(expected_identity(), started_at));
  EXPECT_TRUE(permit.allows_processing());

  permit.report_renewal_uncertainty(started_at);
  permit.evaluate_at(started_at + 4s);
  EXPECT_TRUE(permit.allows_processing());

  permit.evaluate_at(started_at + 5s);
  EXPECT_FALSE(permit.allows_processing());
  EXPECT_EQ(permit.status().state, PartitionOwnershipState::kSelfFenced);
  EXPECT_EQ(permit.status().reason, "LEASE_RENEWAL_UNCONFIRMED");
}

TEST(LeaseFencedPartitionOwnershipPermitTest, RefusesALeaseHeldByAnotherRuntimeIdentity) {
  LeaseFencedPartitionOwnershipPermit permit(expected_identity(), 5s);
  const PartitionOwnershipIdentity different_holder{
      0, "matching-0:pod-uid-replacement", "2026-08-11-regular"};

  EXPECT_FALSE(permit.confirm_renewal(different_holder, std::chrono::steady_clock::time_point{}));
  EXPECT_FALSE(permit.allows_processing());
  EXPECT_EQ(permit.status().state, PartitionOwnershipState::kSelfFenced);
  EXPECT_EQ(permit.status().reason, "LEASE_HOLDER_IDENTITY_MISMATCH");
}

} // namespace
} // namespace simplematch::matching
