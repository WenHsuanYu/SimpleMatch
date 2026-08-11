#pragma once

#include <chrono>
#include <cstdint>
#include <optional>
#include <string>

namespace simplematch::matching {

/** The lifecycle of the infrastructure-owned permit for one fixed Matching partition. */
enum class PartitionOwnershipState { kAwaitingLease, kPermitted, kLeaseUncertain, kSelfFenced };

/** The Lease facts that bind one permit to one Pod, partition, and trading session. */
struct PartitionOwnershipIdentity {
  std::int32_t partition_id;
  std::string holder_identity;
  std::string trading_session_id;

  bool operator==(const PartitionOwnershipIdentity &) const = default;
};

/** Readiness-safe state exported by the infrastructure adapter without Kubernetes API types. */
struct PartitionOwnershipStatus {
  PartitionOwnershipState state;
  std::string reason;

  bool operator==(const PartitionOwnershipStatus &) const = default;
};

/**
 * Infrastructure-derived permission required before one runtime may process its fixed partition.
 *
 * <p>The matching core only reads this interface. A Kubernetes adapter translates Lease observations
 * into the concrete permit lifecycle and never exposes Kubernetes types to the core.</p>
 */
class PartitionOwnershipPermit {
public:
  virtual ~PartitionOwnershipPermit() = default;

  [[nodiscard]] virtual std::int32_t partition_id() const = 0;
  [[nodiscard]] virtual bool allows_processing() const = 0;
  [[nodiscard]] virtual PartitionOwnershipStatus status() const = 0;
};

/**
 * Lease-observation adapter that self-fences one runtime after a bounded renewal uncertainty.
 *
 * <p>The Kubernetes watch supplies the observed holder facts and drives {@link evaluate_at} at a
 * bounded interval. Once fenced, this runtime must be restarted and reacquire a fresh permit; it
 * cannot resume processing from a late observation.</p>
 */
class LeaseFencedPartitionOwnershipPermit final : public PartitionOwnershipPermit {
public:
  LeaseFencedPartitionOwnershipPermit(
      PartitionOwnershipIdentity expected_identity,
      std::chrono::steady_clock::duration self_fence_after);

  /** Records a confirmed renewal only when it still belongs to this runtime identity. */
  [[nodiscard]] bool confirm_renewal(
      const PartitionOwnershipIdentity &observed_identity,
      std::chrono::steady_clock::time_point observed_at);

  /** Starts the bounded grace period after the adapter cannot confirm a renewal. */
  void report_renewal_uncertainty(std::chrono::steady_clock::time_point observed_at);

  /** Applies the self-fencing deadline from the adapter's periodic ownership check. */
  void evaluate_at(std::chrono::steady_clock::time_point observed_at);

  [[nodiscard]] std::int32_t partition_id() const override;
  [[nodiscard]] bool allows_processing() const override;
  [[nodiscard]] PartitionOwnershipStatus status() const override;

private:
  void self_fence(std::string reason);

  PartitionOwnershipIdentity expected_identity_;
  std::chrono::steady_clock::duration self_fence_after_;
  std::optional<std::chrono::steady_clock::time_point> uncertainty_started_at_;
  PartitionOwnershipStatus status_{PartitionOwnershipState::kAwaitingLease,
                                   "LEASE_NOT_CONFIRMED"};
};

} // namespace simplematch::matching
