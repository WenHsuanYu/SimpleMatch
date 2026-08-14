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
  std::lock_guard lock(mutex_);
  if (state_.load(std::memory_order_acquire) == PartitionOwnershipState::kSelfFenced) {
    return false;
  }
  if (observed_identity != expected_identity_) {
    self_fence_locked("LEASE_HOLDER_IDENTITY_MISMATCH");
    return false;
  }
  {
    uncertainty_started_at_.reset();
    reason_ = "LEASE_RENEWED";
  }
  has_confirmed_renewal_.store(true, std::memory_order_release);
  state_.store(PartitionOwnershipState::kPermitted, std::memory_order_release);
  return true;
}

void LeaseFencedPartitionOwnershipPermit::report_renewal_uncertainty(
    std::chrono::steady_clock::time_point observed_at) {
  std::lock_guard lock(mutex_);
  if (state_.load(std::memory_order_acquire) == PartitionOwnershipState::kSelfFenced) {
    return;
  }
  if (!uncertainty_started_at_.has_value()) {
    uncertainty_started_at_ = observed_at;
  }
  reason_ = "LEASE_RENEWAL_UNCERTAIN";
  state_.store(PartitionOwnershipState::kLeaseUncertain, std::memory_order_release);
}

void LeaseFencedPartitionOwnershipPermit::evaluate_at(
    std::chrono::steady_clock::time_point observed_at) {
  std::lock_guard lock(mutex_);
  if (state_.load(std::memory_order_acquire) == PartitionOwnershipState::kSelfFenced ||
      !uncertainty_started_at_.has_value() || observed_at < *uncertainty_started_at_) {
    return;
  }
  if (observed_at - *uncertainty_started_at_ >= self_fence_after_) {
    self_fence_locked("LEASE_RENEWAL_UNCONFIRMED");
  }
}

std::int32_t LeaseFencedPartitionOwnershipPermit::partition_id() const {
  return expected_identity_.partition_id;
}

bool LeaseFencedPartitionOwnershipPermit::allows_processing() const {
  const auto state = state_.load(std::memory_order_acquire);
  const bool confirmed = has_confirmed_renewal_.load(std::memory_order_acquire);
  return state == PartitionOwnershipState::kPermitted ||
         (state == PartitionOwnershipState::kLeaseUncertain && confirmed);
}

PartitionOwnershipStatus LeaseFencedPartitionOwnershipPermit::status() const {
  std::lock_guard lock(mutex_);
  return {state_.load(std::memory_order_acquire), reason_};
}

void LeaseFencedPartitionOwnershipPermit::self_fence(std::string reason) {
  std::lock_guard lock(mutex_);
  self_fence_locked(std::move(reason));
}

void LeaseFencedPartitionOwnershipPermit::self_fence_locked(std::string reason) {
  uncertainty_started_at_.reset();
  state_.store(PartitionOwnershipState::kSelfFenced, std::memory_order_release);
  reason_ = std::move(reason);
}

} // namespace simplematch::matching
