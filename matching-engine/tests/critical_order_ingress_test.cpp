#include "simplematch/matching/ingress/critical_order_ingress.hpp"

#include <cctype>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

#include <gtest/gtest.h>

namespace simplematch::matching {
namespace {

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

AcceptedOrderDelivery delivery(std::int64_t offset = 42) {
  return {{"orders.validated", 7, offset}, "event-1", accepted_order_fixture()};
}

TEST(CriticalOrderIngressTest, ProceedsForAValidOrderOnItsPolicyPartition) {
  CriticalOrderIngress ingress(16, 3);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  EXPECT_EQ(ingress.evaluate(delivery()),
            (CriticalIngressDecision{CriticalIngressAction::kProceed,
                                     {"orders.validated", 7, 42}, 0,
                                     "ROUTING_INGRESS_ACCEPTED", std::nullopt}));
}

TEST(CriticalOrderIngressTest, RetriesTransientFailureAtTheSamePosition) {
  CriticalOrderIngress ingress(16, 3);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  const auto retry = ingress.record_failure(delivery(), "temporary database outage");

  EXPECT_EQ(retry.action, CriticalIngressAction::kRetry);
  EXPECT_EQ(retry.position, (DeliveryPosition{"orders.validated", 7, 42}));
  EXPECT_EQ(retry.attempts, 1);
  EXPECT_EQ(ingress.evaluate(delivery()).action, CriticalIngressAction::kProceed);
}

TEST(CriticalOrderIngressTest, QuarantinesAfterRetryBudgetAndPausesThePartition) {
  CriticalOrderIngress ingress(16, 2);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  ASSERT_EQ(ingress.record_failure(delivery(), "temporary database outage").action,
            CriticalIngressAction::kRetry);
  const auto quarantined = ingress.record_failure(delivery(), "retry budget exhausted");

  ASSERT_EQ(quarantined.action, CriticalIngressAction::kQuarantine);
  ASSERT_TRUE(quarantined.quarantine.has_value());
  EXPECT_EQ(quarantined.quarantine->event_id, "event-1");
  EXPECT_EQ(quarantined.quarantine->position,
            (DeliveryPosition{"orders.validated", 7, 42}));
  EXPECT_EQ(ingress.evaluate(delivery()).action, CriticalIngressAction::kPause);
}

TEST(CriticalOrderIngressTest, RestoresEvidenceAndResumesTheSameRecordAfterRestart) {
  CriticalOrderIngress first(16, 1);
  ASSERT_EQ(first.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);
  const auto quarantined = first.record_failure(delivery(), "operator review");
  ASSERT_TRUE(quarantined.quarantine.has_value());

  CriticalOrderIngress restarted(16, 1);
  ASSERT_EQ(restarted.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);
  EXPECT_EQ(restarted.restore_quarantine(*quarantined.quarantine).action,
            CriticalIngressAction::kPause);
  EXPECT_EQ(restarted.resume(delivery()),
            (CriticalIngressDecision{CriticalIngressAction::kProceed,
                                     {"orders.validated", 7, 42}, 1,
                                     "CRITICAL_INGRESS_RECOVERED", std::nullopt}));
}

TEST(CriticalOrderIngressTest, StopsKnownPolicyPartitionMismatchWithoutRerouting) {
  CriticalOrderIngress ingress(16, 3);
  ASSERT_EQ(ingress.ingest_routing_policy(policy_fixture()).action,
            IngressAction::kProceed);

  auto wrong_partition = delivery();
  wrong_partition.position.partition = 8;

  const auto decision = ingress.evaluate(wrong_partition);
  EXPECT_EQ(decision.action, CriticalIngressAction::kStop);
  EXPECT_EQ(decision.reason, "ROUTING_PARTITION_MISMATCH");
  EXPECT_EQ(decision.position.partition, 8);
}

} // namespace
} // namespace simplematch::matching
