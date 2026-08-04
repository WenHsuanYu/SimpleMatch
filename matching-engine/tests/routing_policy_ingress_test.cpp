#include "simplematch/matching/ingress/routing_policy_ingress.hpp"

#include <string>

#include <gtest/gtest.h>

#include "common_v2.pb.h"
#include "orders_v2.pb.h"
#include "routing_policy_v2.pb.h"

namespace simplematch::matching {
namespace {

constexpr char kPolicyId[] = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c01";

simplematch::routing::v2::RoutingPolicy policy_fixture() {
  simplematch::routing::v2::RoutingPolicy policy;
  policy.mutable_metadata()->set_schema_version("v2");
  policy.mutable_metadata()->set_event_id(
      "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c02");
  policy.mutable_metadata()->set_created_at_unix_ms(1'753'176'000'000);
  policy.mutable_metadata()->set_source_service("marketdata-publisher");
  policy.mutable_metadata()->set_correlation_id(kPolicyId);
  policy.set_routing_policy_id(kPolicyId);
  policy.set_source_market_snapshot_id("0194a8ef-3b42-7e6c-8e19-7f3c2d0a1001");
  policy.mutable_trading_day()->set_iso_date("2026-07-27");
  policy.set_effective_from_unix_ms(1'753'171'200'000);
  policy.set_effective_until_unix_ms(1'753'192'800'000);
  policy.set_orders_validated_partition_count(16);
  auto *assignment = policy.add_assignments();
  assignment->mutable_instrument()->set_symbol("AAPL");
  assignment->mutable_instrument()->set_venue_mic("XTAI");
  assignment->set_routing_partition(7);
  return policy;
}

simplematch::orders::v2::OrderAdmissionAccepted accepted_order_fixture() {
  simplematch::orders::v2::OrderAdmissionAccepted order;
  order.set_command_id("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c03");
  order.set_order_id("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c04");
  order.set_account_id("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c05");
  order.mutable_instrument()->set_symbol("aapl");
  order.mutable_instrument()->set_venue_mic("xtai");
  order.set_routing_policy_id(kPolicyId);
  order.set_routing_partition(7);
  return order;
}

TEST(RoutingPolicyIngressTest, DecodesSharedPolicyAndAcceptedOrderFixture) {
  RoutingPolicyIngress ingress(16);

  EXPECT_EQ(ingress.ingest_routing_policy(policy_fixture().SerializeAsString())
                .action,
            IngressAction::kProceed);
  EXPECT_EQ(ingress
                .evaluate_accepted_order(
                    accepted_order_fixture().SerializeAsString(), 7)
                .action,
            IngressAction::kProceed);
}

TEST(RoutingPolicyIngressTest, PausesOrderUntilReferencedPolicyIsProjected) {
  RoutingPolicyIngress ingress(16);

  const auto decision = ingress.evaluate_accepted_order(
      accepted_order_fixture().SerializeAsString(), 7);

  EXPECT_EQ(decision, (IngressDecision{IngressAction::kPause,
                                       "ROUTING_POLICY_NOT_PROJECTED"}));
}

TEST(RoutingPolicyIngressTest, StopsMalformedOrPartitionMismatchedInput) {
  RoutingPolicyIngress ingress(16);
  ingress.ingest_routing_policy(policy_fixture().SerializeAsString());

  EXPECT_EQ(ingress.ingest_routing_policy("not-protobuf").action,
            IngressAction::kStop);
  EXPECT_EQ(ingress
                .evaluate_accepted_order(
                    accepted_order_fixture().SerializeAsString(), 8)
                .action,
            IngressAction::kStop);
}

} // namespace
} // namespace simplematch::matching
