#pragma once

#include <cstdint>
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

  IngressDecision ingest_routing_policy(std::string_view payload);
  IngressDecision
  evaluate_accepted_order(std::string_view payload,
                          std::int32_t consumed_partition) const;

private:
  struct PolicyState {
    std::int32_t partition_count;
    std::unordered_map<std::string, std::int32_t> assignments;
  };

  std::int32_t expected_partition_count_;
  std::unordered_map<std::string, PolicyState> policies_;
};

} // namespace simplematch::matching
