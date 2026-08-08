package com.simplematch.marketdatapublisher.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingPolicyOutboxRecordTest {
  @Test
  void preservesTheExplicitPolicyPartition() {
    final RoutingPolicyOutboxRecord record =
        new RoutingPolicyOutboxRecord(
            new RoutingPolicyOutboxRecord.EventIdentity(UUID.randomUUID()),
            new RoutingPolicyOutboxRecord.Destination(
                RoutingPolicyApplicationService.ROUTING_POLICY_PUBLISHED_TOPIC,
                "2026-07-27",
                RoutingPolicyApplicationService.ROUTING_POLICY_PARTITION),
            new RoutingPolicyOutboxRecord.Payload(new byte[] {1}, "policy.v2", "{}"),
            new RoutingPolicyOutboxRecord.AggregateReference("routing_policy", "policy-1"),
            1L);

    assertThat(record.destination().kafkaPartitionId()).isEqualTo(0);
    assertThat(record.destination().topic()).isEqualTo("market-reference.routing-policies");
    assertThat(record.destination().messageKey()).isEqualTo("2026-07-27");
  }

  @Test
  void rejectsNegativePolicyPartitions() {
    assertThatThrownBy(
            () -> new RoutingPolicyOutboxRecord.Destination("routing", "2026-07-27", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("kafka partition must be non-negative");
  }
}
