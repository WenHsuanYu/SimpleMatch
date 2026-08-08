#include "simplematch/matching/ingress/critical_order_ingress.hpp"

#include <stdexcept>
#include <utility>

namespace simplematch::matching {
namespace {

constexpr char kRetry[] = "CRITICAL_INGRESS_RETRY";
constexpr char kPaused[] = "CRITICAL_INGRESS_PAUSED";
constexpr char kQuarantined[] = "CRITICAL_INGRESS_QUARANTINED";
constexpr char kRecovered[] = "CRITICAL_INGRESS_RECOVERED";
constexpr char kNotQuarantined[] = "CRITICAL_INGRESS_NOT_QUARANTINED";
constexpr char kEventMismatch[] = "CRITICAL_INGRESS_EVENT_MISMATCH";

} // namespace

CriticalOrderIngress::CriticalOrderIngress(
    std::int32_t expected_partition_count, std::int32_t maximum_attempts)
    : routing_policy_ingress_(expected_partition_count),
      maximum_attempts_(maximum_attempts) {
  if (maximum_attempts <= 0) {
    throw std::invalid_argument("maximum_attempts must be positive");
  }
}

IngressDecision CriticalOrderIngress::ingest_routing_policy(
    std::string_view payload) {
  return routing_policy_ingress_.ingest_routing_policy(payload);
}

CriticalIngressDecision CriticalOrderIngress::evaluate(
    const AcceptedOrderDelivery &delivery) const {
  validate_delivery(delivery);
  const auto quarantined = quarantined_.find(delivery.position);
  if (quarantined != quarantined_.end()) {
    return decision(CriticalIngressAction::kPause, delivery.position,
                    quarantined->second.attempts, kQuarantined,
                    quarantined->second);
  }

  const auto policy = policy_decision(delivery);
  if (policy.action != CriticalIngressAction::kProceed) {
    return policy;
  }
  return decision(CriticalIngressAction::kProceed, delivery.position, 0,
                  policy.reason);
}

CriticalIngressDecision CriticalOrderIngress::record_failure(
    const AcceptedOrderDelivery &delivery, std::string reason) {
  validate_delivery(delivery);
  if (reason.empty()) {
    throw std::invalid_argument("failure reason must not be empty");
  }

  const auto current = evaluate(delivery);
  if (current.action == CriticalIngressAction::kStop ||
      current.action == CriticalIngressAction::kPause) {
    return current;
  }

  const auto next_attempt = attempts_[delivery.position] + 1;
  if (next_attempt < maximum_attempts_) {
    attempts_[delivery.position] = next_attempt;
    return decision(CriticalIngressAction::kRetry, delivery.position,
                    next_attempt, kRetry);
  }

  const QuarantineEvidence evidence{delivery.position, delivery.event_id,
                                    next_attempt, std::move(reason)};
  quarantined_[delivery.position] = evidence;
  attempts_.erase(delivery.position);
  return decision(CriticalIngressAction::kQuarantine, delivery.position,
                  next_attempt, kQuarantined, evidence);
}

CriticalIngressDecision CriticalOrderIngress::restore_quarantine(
    QuarantineEvidence evidence) {
  validate_position(evidence.position);
  if (evidence.event_id.empty() || evidence.attempts <= 0 ||
      evidence.reason.empty()) {
    throw std::invalid_argument("quarantine evidence is incomplete");
  }
  const auto [existing, inserted] =
      quarantined_.emplace(evidence.position, evidence);
  if (!inserted && existing->second != evidence) {
    return decision(CriticalIngressAction::kStop, evidence.position,
                    evidence.attempts, "CRITICAL_INGRESS_QUARANTINE_CONFLICT",
                    existing->second);
  }
  return decision(CriticalIngressAction::kPause, evidence.position,
                  evidence.attempts, kQuarantined, evidence);
}

CriticalIngressDecision CriticalOrderIngress::resume(
    const AcceptedOrderDelivery &delivery) {
  validate_delivery(delivery);
  const auto quarantined = quarantined_.find(delivery.position);
  if (quarantined == quarantined_.end()) {
    return decision(CriticalIngressAction::kStop, delivery.position, 0,
                    kNotQuarantined);
  }
  if (quarantined->second.event_id != delivery.event_id) {
    return decision(CriticalIngressAction::kStop, delivery.position,
                    quarantined->second.attempts, kEventMismatch,
                    quarantined->second);
  }

  const auto policy = policy_decision(delivery);
  if (policy.action != CriticalIngressAction::kProceed) {
    return policy;
  }

  const auto attempts = quarantined->second.attempts;
  quarantined_.erase(quarantined);
  return decision(CriticalIngressAction::kProceed, delivery.position, attempts,
                  kRecovered);
}

CriticalIngressDecision CriticalOrderIngress::policy_decision(
    const AcceptedOrderDelivery &delivery) const {
  const auto route = routing_policy_ingress_.evaluate_accepted_order(
      delivery.payload, delivery.position.partition);
  if (route.action == IngressAction::kProceed) {
    return decision(CriticalIngressAction::kProceed, delivery.position, 0,
                    route.reason);
  }
  if (route.action == IngressAction::kPause) {
    return decision(CriticalIngressAction::kPause, delivery.position, 0,
                    route.reason);
  }
  return decision(CriticalIngressAction::kStop, delivery.position, 0,
                  route.reason);
}

void CriticalOrderIngress::validate_position(const DeliveryPosition &position) {
  if (position.topic.empty() || position.partition < 0 || position.offset < 0) {
    throw std::invalid_argument("delivery position is invalid");
  }
}

void CriticalOrderIngress::validate_delivery(
    const AcceptedOrderDelivery &delivery) {
  validate_position(delivery.position);
  if (delivery.event_id.empty() || delivery.payload.empty()) {
    throw std::invalid_argument("accepted order delivery is incomplete");
  }
}

CriticalIngressDecision CriticalOrderIngress::decision(
    CriticalIngressAction action, const DeliveryPosition &position,
    std::int32_t attempts, std::string reason,
    std::optional<QuarantineEvidence> quarantine) {
  return {action, position, attempts, std::move(reason), std::move(quarantine)};
}

} // namespace simplematch::matching
