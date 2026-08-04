#include "simplematch/matching/ingress/routing_policy_ingress.hpp"

#include <cctype>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

constexpr char kPolicyId[] = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01";
constexpr std::int64_t kInsidePolicyInterval = 1'753'180'000'000;

int hex_value(char character) {
  if (character >= '0' && character <= '9') {
    return character - '0';
  }
  if (character >= 'a' && character <= 'f') {
    return character - 'a' + 10;
  }
  if (character >= 'A' && character <= 'F') {
    return character - 'A' + 10;
  }
  return -1;
}

std::string load_hex_fixture(std::string_view name) {
  std::ifstream fixture(std::string(SIMPLEMATCH_TEST_FIXTURE_DIR) + "/" +
                        std::string(name));
  if (!fixture) {
    return {};
  }
  const std::string encoded((std::istreambuf_iterator<char>(fixture)), {});
  std::string compact;
  for (const char character : encoded) {
    if (!std::isspace(static_cast<unsigned char>(character))) {
      compact.push_back(character);
    }
  }
  if (compact.empty() || compact.size() % 2 != 0) {
    return {};
  }
  std::string decoded;
  decoded.reserve(compact.size() / 2);
  for (std::size_t index = 0; index < compact.size(); index += 2) {
    const int high = hex_value(compact[index]);
    const int low = hex_value(compact[index + 1]);
    if (high < 0 || low < 0) {
      return {};
    }
    decoded.push_back(static_cast<char>((high << 4) | low));
  }
  return decoded;
}

std::string policy_fixture() {
  return load_hex_fixture("java-routing-policy-v2.hex");
}

std::string accepted_order_fixture() {
  return load_hex_fixture("java-order-admission-accepted-v2.hex");
}

TEST(RoutingPolicyIngressTest,
     StagesJavaContractFixtureBeforeAtomicActivation) {
  RoutingPolicyIngress ingress(16);
  const auto policy = policy_fixture();
  const auto order = accepted_order_fixture();
  ASSERT_FALSE(policy.empty());
  ASSERT_FALSE(order.empty());

  EXPECT_EQ(
      ingress.stage_routing_policy(policy),
      (IngressDecision{IngressAction::kProceed, "ROUTING_POLICY_STAGED"}));
  EXPECT_EQ(
      ingress.evaluate_accepted_order(order, 7),
      (IngressDecision{IngressAction::kPause, "ROUTING_POLICY_NOT_PROJECTED"}));
  EXPECT_EQ(
      ingress.evaluate_routing_policy_readiness(kPolicyId,
                                                kInsidePolicyInterval),
      (IngressDecision{IngressAction::kPause, "ROUTING_POLICY_NOT_PROJECTED"}));

  EXPECT_EQ(
      ingress.activate_staged_routing_policy(kPolicyId),
      (IngressDecision{IngressAction::kProceed, "ROUTING_INGRESS_ACCEPTED"}));
  EXPECT_EQ(ingress.evaluate_routing_policy_readiness(kPolicyId,
                                                      kInsidePolicyInterval),
            (IngressDecision{IngressAction::kProceed, "ROUTING_POLICY_READY"}));
  EXPECT_EQ(
      ingress.evaluate_accepted_order(order, 7),
      (IngressDecision{IngressAction::kProceed, "ROUTING_INGRESS_ACCEPTED"}));
}

TEST(RoutingPolicyIngressTest, PausesOrderUntilReferencedPolicyIsProjected) {
  RoutingPolicyIngress ingress(16);

  EXPECT_EQ(
      ingress.evaluate_accepted_order(accepted_order_fixture(), 7),
      (IngressDecision{IngressAction::kPause, "ROUTING_POLICY_NOT_PROJECTED"}));
}

TEST(RoutingPolicyIngressTest, ReplaysPolicyAfterRestartBeforeProcessingOrder) {
  RoutingPolicyIngress ingress(16);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  RoutingPolicyIngress restarted(16);
  EXPECT_EQ(
      restarted.evaluate_accepted_order(accepted_order_fixture(), 7),
      (IngressDecision{IngressAction::kPause, "ROUTING_POLICY_NOT_PROJECTED"}));
  EXPECT_EQ(
      restarted.ingest_routing_policy(policy_fixture()),
      (IngressDecision{IngressAction::kProceed, "ROUTING_INGRESS_ACCEPTED"}));
  EXPECT_EQ(
      restarted.evaluate_accepted_order(accepted_order_fixture(), 7),
      (IngressDecision{IngressAction::kProceed, "ROUTING_INGRESS_ACCEPTED"}));
}

TEST(RoutingPolicyIngressTest, StopsMalformedOrPartitionMismatchedInput) {
  RoutingPolicyIngress ingress(16);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  EXPECT_EQ(ingress.ingest_routing_policy("not-protobuf"),
            (IngressDecision{IngressAction::kStop, "INVALID_ROUTING_POLICY"}));
  EXPECT_EQ(
      ingress.evaluate_accepted_order(accepted_order_fixture(), 8),
      (IngressDecision{IngressAction::kStop, "ROUTING_PARTITION_MISMATCH"}));
}

} // namespace
} // namespace simplematch::matching
