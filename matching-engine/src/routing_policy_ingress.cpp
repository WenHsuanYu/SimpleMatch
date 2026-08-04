#include "simplematch/matching/ingress/routing_policy_ingress.hpp"

#include <algorithm>
#include <cctype>
#include <limits>
#include <string>
#include <unordered_map>

#include "orders_v2.pb.h"
#include "routing_policy_v2.pb.h"

namespace simplematch::matching {
namespace {

constexpr char kInvalidPolicy[] = "INVALID_ROUTING_POLICY";
constexpr char kInvalidOrder[] = "INVALID_ACCEPTED_ORDER";
constexpr char kPolicyNotProjected[] = "ROUTING_POLICY_NOT_PROJECTED";
constexpr char kUnknownInstrument[] = "ROUTING_INSTRUMENT_NOT_ASSIGNED";
constexpr char kPartitionMismatch[] = "ROUTING_PARTITION_MISMATCH";
constexpr char kAccepted[] = "ROUTING_INGRESS_ACCEPTED";

std::string normalize(std::string value) {
  value.erase(value.begin(), std::find_if(value.begin(), value.end(),
                                          [](unsigned char character) {
                                            return !std::isspace(character);
                                          }));
  value.erase(std::find_if(value.rbegin(), value.rend(),
                           [](unsigned char character) {
                             return !std::isspace(character);
                           })
                  .base(),
              value.end());
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char character) {
                   return static_cast<char>(std::toupper(character));
                 });
  return value;
}

std::string instrument_key(std::string_view symbol,
                           std::string_view venue_mic) {
  const auto normalized_symbol = normalize(std::string(symbol));
  const auto normalized_venue = normalize(std::string(venue_mic));
  if (normalized_symbol.empty() || normalized_venue.empty()) {
    return {};
  }
  return normalized_symbol + '\x1f' + normalized_venue;
}

bool valid_partition(std::int32_t partition, std::int32_t partition_count) {
  return partition >= 0 && partition < partition_count;
}

bool payload_size_fits_protobuf(std::string_view payload) {
  return payload.size() <=
         static_cast<std::size_t>(std::numeric_limits<int>::max());
}

} // namespace

RoutingPolicyIngress::RoutingPolicyIngress(
    std::int32_t expected_partition_count)
    : expected_partition_count_(expected_partition_count) {}

IngressDecision
RoutingPolicyIngress::ingest_routing_policy(std::string_view payload) {
  if (!payload_size_fits_protobuf(payload)) {
    return {IngressAction::kStop, kInvalidPolicy};
  }
  simplematch::routing::v2::RoutingPolicy policy;
  if (!policy.ParseFromArray(payload.data(),
                             static_cast<int>(payload.size()))) {
    return {IngressAction::kStop, kInvalidPolicy};
  }
  if (policy.routing_policy_id().empty() ||
      policy.orders_validated_partition_count() != expected_partition_count_ ||
      policy.orders_validated_partition_count() <= 0 ||
      policy.assignments().empty()) {
    return {IngressAction::kStop, kInvalidPolicy};
  }

  PolicyState state{policy.orders_validated_partition_count(), {}};
  for (const auto &assignment : policy.assignments()) {
    const auto key = instrument_key(assignment.instrument().symbol(),
                                    assignment.instrument().venue_mic());
    if (key.empty() ||
        !valid_partition(assignment.routing_partition(),
                         state.partition_count) ||
        !state.assignments.emplace(key, assignment.routing_partition())
             .second) {
      return {IngressAction::kStop, kInvalidPolicy};
    }
  }
  policies_[policy.routing_policy_id()] = std::move(state);
  return {IngressAction::kProceed, kAccepted};
}

IngressDecision RoutingPolicyIngress::evaluate_accepted_order(
    std::string_view payload, std::int32_t consumed_partition) const {
  if (!payload_size_fits_protobuf(payload)) {
    return {IngressAction::kStop, kInvalidOrder};
  }
  simplematch::orders::v2::OrderAdmissionAccepted order;
  if (!order.ParseFromArray(payload.data(), static_cast<int>(payload.size()))) {
    return {IngressAction::kStop, kInvalidOrder};
  }
  if (order.routing_policy_id().empty() ||
      order.instrument().symbol().empty() ||
      order.instrument().venue_mic().empty() || consumed_partition < 0 ||
      instrument_key(order.instrument().symbol(),
                     order.instrument().venue_mic())
          .empty()) {
    return {IngressAction::kStop, kInvalidOrder};
  }
  const auto policy = policies_.find(order.routing_policy_id());
  if (policy == policies_.end()) {
    return {IngressAction::kPause, kPolicyNotProjected};
  }
  const auto assignment = policy->second.assignments.find(instrument_key(
      order.instrument().symbol(), order.instrument().venue_mic()));
  if (assignment == policy->second.assignments.end()) {
    return {IngressAction::kStop, kUnknownInstrument};
  }
  if (order.routing_partition() != assignment->second ||
      consumed_partition != assignment->second) {
    return {IngressAction::kStop, kPartitionMismatch};
  }
  return {IngressAction::kProceed, kAccepted};
}

} // namespace simplematch::matching
