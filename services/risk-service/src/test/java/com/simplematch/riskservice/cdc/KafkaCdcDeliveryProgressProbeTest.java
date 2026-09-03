package com.simplematch.riskservice.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;

class KafkaCdcDeliveryProgressProbeTest {
  private static final String TOPIC = "matching.commands";
  private static final String GROUP = "risk-cdc-delivery";

  @Test
  void reportsCaughtUpOnlyWhenEveryCommittedOffsetReachedItsTopicHead() {
    final Admin admin = adminWithOffsets(Map.of(partition(0), 12L, partition(1), 9L));

    final boolean caughtUp =
        new KafkaCdcDeliveryProgressProbe(admin, GROUP, Duration.ofSeconds(1))
            .isCaughtUp(TOPIC);

    assertThat(caughtUp).isTrue();
  }

  @Test
  void reportsBehindWhenAHeadHasNoCommittedObserverOffset() {
    final Admin admin = adminWithOffsets(Map.of(partition(0), 12L));

    final boolean caughtUp =
        new KafkaCdcDeliveryProgressProbe(admin, GROUP, Duration.ofSeconds(1))
            .isCaughtUp(TOPIC);

    assertThat(caughtUp).isFalse();
  }

  @Test
  void treatsAnEmptyTopicWithNoCommittedOffsetsAsHealthyZeroTraffic() {
    final Admin admin = adminWithOffsets(Map.of(), Map.of(partition(0), 0L, partition(1), 0L));

    final boolean caughtUp =
        new KafkaCdcDeliveryProgressProbe(admin, GROUP, Duration.ofSeconds(1))
            .isCaughtUp(TOPIC);

    assertThat(caughtUp).isTrue();
  }

  private Admin adminWithOffsets(Map<TopicPartition, Long> committedOffsets) {
    return adminWithOffsets(committedOffsets, Map.of(partition(0), 12L, partition(1), 9L));
  }

  private Admin adminWithOffsets(
      Map<TopicPartition, Long> committedOffsets, Map<TopicPartition, Long> endOffsetsByPartition) {
    final Admin admin = mock(Admin.class);
    final DescribeTopicsResult descriptions = mock(DescribeTopicsResult.class);
    final ListOffsetsResult offsets = mock(ListOffsetsResult.class);
    final ListConsumerGroupOffsetsResult groupOffsets = mock(ListConsumerGroupOffsetsResult.class);
    final TopicDescription topic =
        new TopicDescription(
            TOPIC,
            false,
            List.of(partitionInfo(0), partitionInfo(1)));
    when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class)))
        .thenReturn(descriptions);
    when(descriptions.allTopicNames())
        .thenReturn(KafkaFuture.completedFuture(Map.of(TOPIC, topic)));
    when(admin.listOffsets(
            org.mockito.ArgumentMatchers.<Map<TopicPartition, OffsetSpec>>any(),
            any(ListOffsetsOptions.class)))
        .thenReturn(offsets);
    when(offsets.all())
        .thenReturn(
            KafkaFuture.completedFuture(
                Map.of(
                    partition(0), offsetInfo(endOffsetsByPartition.get(partition(0))),
                    partition(1), offsetInfo(endOffsetsByPartition.get(partition(1))))));
    when(admin.listConsumerGroupOffsets(eq(GROUP), any(ListConsumerGroupOffsetsOptions.class)))
        .thenReturn(groupOffsets);
    when(groupOffsets.partitionsToOffsetAndMetadata())
        .thenReturn(
            KafkaFuture.completedFuture(
                committedOffsets.entrySet().stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> new OffsetAndMetadata(entry.getValue())))));
    return admin;
  }

  private TopicPartitionInfo partitionInfo(int partition) {
    final Node node = Node.noNode();
    return new TopicPartitionInfo(partition, node, List.of(node), List.of(node));
  }

  private TopicPartition partition(int partition) {
    return new TopicPartition(TOPIC, partition);
  }

  private ListOffsetsResult.ListOffsetsResultInfo offsetInfo(long offset) {
    return new ListOffsetsResult.ListOffsetsResultInfo(offset, 0L, Optional.empty());
  }
}
