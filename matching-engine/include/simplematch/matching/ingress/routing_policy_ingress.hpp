#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

namespace simplematch::matching {

enum class IngressAction { kProceed, kPause, kStop };

struct IngressDecision {
  IngressAction action;
  std::string reason;

  bool operator==(const IngressDecision &) const = default;
};

class RoutingPolicyIngress {
public:
  explicit RoutingPolicyIngress(std::int32_t expected_partition_count);

  IngressDecision stage_routing_policy(std::string_view payload);
  IngressDecision
  activate_staged_routing_policy(std::string_view routing_policy_id);
  IngressDecision ingest_routing_policy(std::string_view payload);
  IngressDecision
  evaluate_accepted_order(std::string_view payload,
                          std::int32_t consumed_partition) const;
  IngressDecision
  evaluate_routing_policy_readiness(std::string_view routing_policy_id,
                                    std::int64_t now_unix_ms) const;

private:
  struct PolicyState {
    std::int32_t partition_count;
    std::string trading_day;
    std::int64_t effective_from_unix_ms;
    std::int64_t effective_until_unix_ms;
    std::unordered_map<std::string, std::int32_t> assignments;

    bool operator==(const PolicyState &) const = default;
  };

  struct DecodedPolicy {
    std::string routing_policy_id;
    PolicyState state;
  };

  std::optional<DecodedPolicy>
  decode_routing_policy(std::string_view payload) const;
  IngressDecision stage_decoded_policy(std::string routing_policy_id,
                                       PolicyState state);

  std::int32_t expected_partition_count_;
  std::unordered_map<std::string, PolicyState> staged_policies_;
  std::unordered_map<std::string, PolicyState> policies_;
};

} // namespace simplematch::matching
