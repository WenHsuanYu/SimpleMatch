#pragma once

#include <compare>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>

#include "simplematch/matching/ingress/routing_policy_ingress.hpp"

namespace simplematch::matching {

/** Identifies the exact Kafka record that a critical ingress operation handles. */
struct DeliveryPosition {
  std::string topic;
  std::int32_t partition;
  std::int64_t offset;

  auto operator<=>(const DeliveryPosition &) const = default;
};

/** Owns the accepted-order bytes and their immutable delivery identity. */
struct AcceptedOrderDelivery {
  DeliveryPosition position;
  std::string event_id;
  std::string payload;
};

/** Evidence retained when a critical order cannot advance its partition. */
struct QuarantineEvidence {
  DeliveryPosition position;
  std::string event_id;
  std::int32_t attempts;
  std::string reason;

  bool operator==(const QuarantineEvidence &) const = default;
};

enum class CriticalIngressAction {
  kProceed,
  kRetry,
  kPause,
  kQuarantine,
  kStop
};

/** Decision returned by the critical accepted-order delivery state machine. */
struct CriticalIngressDecision {
  CriticalIngressAction action;
  DeliveryPosition position;
  std::int32_t attempts;
  std::string reason;
  std::optional<QuarantineEvidence> quarantine;

  bool operator==(const CriticalIngressDecision &) const = default;
};

/**
 * Keeps an accepted-order partition ordered while policy and business delivery recover.
 *
 * <p>The native boundary does not own durable quarantine storage. It produces
 * {@link QuarantineEvidence} for that storage and can restore the same evidence
 * after a process restart. A failed record is never replaced by a later offset
 * and a policy mismatch is always a stop, never a reroute.</p>
 */
class CriticalOrderIngress {
public:
  CriticalOrderIngress(std::int32_t expected_partition_count,
                       std::int32_t maximum_attempts);

  IngressDecision ingest_routing_policy(std::string_view payload);
  CriticalIngressDecision evaluate(const AcceptedOrderDelivery &delivery) const;
  CriticalIngressDecision record_failure(const AcceptedOrderDelivery &delivery,
                                         std::string reason);
  CriticalIngressDecision restore_quarantine(QuarantineEvidence evidence);
  CriticalIngressDecision resume(const AcceptedOrderDelivery &delivery);

private:
  CriticalIngressDecision policy_decision(const AcceptedOrderDelivery &delivery) const;
  static void validate_position(const DeliveryPosition &position);
  static void validate_delivery(const AcceptedOrderDelivery &delivery);
  static CriticalIngressDecision decision(CriticalIngressAction action,
                                          const DeliveryPosition &position,
                                          std::int32_t attempts,
                                          std::string reason,
                                          std::optional<QuarantineEvidence> quarantine =
                                              std::nullopt);

  RoutingPolicyIngress routing_policy_ingress_;
  std::int32_t maximum_attempts_;
  std::map<DeliveryPosition, std::int32_t> attempts_;
  std::map<DeliveryPosition, QuarantineEvidence> quarantined_;
};

} // namespace simplematch::matching
