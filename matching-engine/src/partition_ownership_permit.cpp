#include "simplematch/matching/runtime/partition_ownership_permit.hpp"

#include <stdexcept>
#include <utility>

namespace simplematch::matching {
namespace {

bool valid_identity(const PartitionOwnershipIdentity &identity) {
  return identity.partition_id >= 0 && !identity.holder_identity.empty() &&
         !identity.trading_session_id.empty();
}

} // namespace

LeaseFencedPartitionOwnershipPermit::LeaseFencedPartitionOwnershipPermit(
    PartitionOwnershipIdentity expected_identity,
    std::chrono::steady_clock::duration self_fence_after)
    : expected_identity_(std::move(expected_identity)), self_fence_after_(self_fence_after) {
  if (!valid_identity(expected_identity_) || self_fence_after_ <= std::chrono::steady_clock::duration::zero()) {
    throw std::invalid_argument("partition ownership permit requires a valid identity and deadline");
  }
}

bool LeaseFencedPartitionOwnershipPermit::confirm_renewal(
    const PartitionOwnershipIdentity &observed_identity,
    std::chrono::steady_clock::time_point observed_at) {
  static_cast<void>(observed_at);
  if (status_.state == PartitionOwnershipState::kSelfFenced) {
    return false;
  }
  if (observed_identity != expected_identity_) {
    self_fence("LEASE_HOLDER_IDENTITY_MISMATCH");
    return false;
  }
  uncertainty_started_at_.reset();
  status_ = {PartitionOwnershipState::kPermitted, "LEASE_RENEWED"};
  return true;
}

void LeaseFencedPartitionOwnershipPermit::report_renewal_uncertainty(
    std::chrono::steady_clock::time_point observed_at) {
  if (status_.state == PartitionOwnershipState::kSelfFenced) {
    return;
  }
  if (!uncertainty_started_at_.has_value()) {
    uncertainty_started_at_ = observed_at;
  }
  status_ = {PartitionOwnershipState::kLeaseUncertain, "LEASE_RENEWAL_UNCERTAIN"};
}

void LeaseFencedPartitionOwnershipPermit::evaluate_at(
    std::chrono::steady_clock::time_point observed_at) {
  if (status_.state == PartitionOwnershipState::kSelfFenced ||
      !uncertainty_started_at_.has_value() || observed_at < *uncertainty_started_at_) {
    return;
  }
  if (observed_at - *uncertainty_started_at_ >= self_fence_after_) {
    self_fence("LEASE_RENEWAL_UNCONFIRMED");
  }
}

std::int32_t LeaseFencedPartitionOwnershipPermit::partition_id() const {
  return expected_identity_.partition_id;
}

bool LeaseFencedPartitionOwnershipPermit::allows_processing() const {
  return status_.state == PartitionOwnershipState::kPermitted ||
         status_.state == PartitionOwnershipState::kLeaseUncertain;
}

PartitionOwnershipStatus LeaseFencedPartitionOwnershipPermit::status() const {
  return status_;
}

void LeaseFencedPartitionOwnershipPermit::self_fence(std::string reason) {
  uncertainty_started_at_.reset();
  status_ = {PartitionOwnershipState::kSelfFenced, std::move(reason)};
}

} // namespace simplematch::matching
