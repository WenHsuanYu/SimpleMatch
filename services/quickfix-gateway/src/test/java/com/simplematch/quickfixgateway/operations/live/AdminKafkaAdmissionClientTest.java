package com.simplematch.quickfixgateway.operations.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simplematch.quickfixgateway.config.GatewayLiveObservationProperties;
import com.simplematch.quickfixgateway.operations.CriticalConsumer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminKafkaAdmissionClientTest {
  private static final String COMMANDS = "matching.commands";
  private static final String EVENTS = "matching.events";
  private static final Instant NOW = Instant.parse("2026-10-05T01:00:00Z");
  private final Admin admin = mock(Admin.class);
  private final Map<String, Map<TopicPartition, OffsetAndMetadata>> groupOffsets = new HashMap<>();
  private long commandEndOffset = 100;

  @BeforeEach
  void configureKafkaResponses() {
    final DescribeTopicsResult descriptions = mock(DescribeTopicsResult.class);
    when(admin.describeTopics(anyCollection())).thenReturn(descriptions);
    when(descriptions.allTopicNames()).thenReturn(KafkaFuture.completedFuture(
        Map.of(COMMANDS, topic(COMMANDS), EVENTS, topic(EVENTS))));
    when(admin.listOffsets(anyMap())).thenAnswer(invocation -> {
      final Map<TopicPartition, OffsetSpec> request = invocation.getArgument(0);
      final Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets = new HashMap<>();
      request.keySet().forEach(partition -> offsets.put(partition,
          new ListOffsetsResult.ListOffsetsResultInfo(
              partition.topic().equals(COMMANDS) ? commandEndOffset : 100,
              -1, Optional.empty())));
      final ListOffsetsResult result = mock(ListOffsetsResult.class);
      when(result.all()).thenReturn(KafkaFuture.completedFuture(offsets));
      return result;
    });
    for (int partition = 0; partition < 15; partition++) {
      groupOffsets.put("matching-partition-consumer-" + partition, Map.of(
          new TopicPartition(COMMANDS, partition), new OffsetAndMetadata(partition + 1),
          new TopicPartition(EVENTS, partition), new OffsetAndMetadata(999)));
    }
    groupOffsets.put("account", Map.of(new TopicPartition(EVENTS, 0), new OffsetAndMetadata(5)));
    when(admin.listConsumerGroupOffsets(anyString())).thenAnswer(invocation -> {
      final String group = invocation.getArgument(0);
      final ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
      when(result.partitionsToOffsetAndMetadata()).thenReturn(
          KafkaFuture.completedFuture(groupOffsets.getOrDefault(group, Map.of())));
      return result;
    });
  }

  @Test
  void readsEachMatchingCommandCommitSeparatelyFromCriticalEventCommits() {
    final KafkaAdmissionSnapshot snapshot = client().observe();

    assertThat(snapshot.matchingCommittedOffsets()).hasSize(15).containsEntry(0, 1L)
        .containsEntry(14, 15L);
    assertThat(snapshot.consumerCommittedOffsets().get(CriticalConsumer.ACCOUNT))
        .containsExactlyEntriesOf(Map.of(0, 5L));
    assertThat(snapshot.observedAt()).isEqualTo(NOW);
  }

  @Test
  void missingMatchingCommitIsNotInventedByTheKafkaAdapter() {
    groupOffsets.remove("matching-partition-consumer-0");

    assertThat(client().observe().matchingCommittedOffsets()).doesNotContainKey(0);
  }

  @Test
  void matchingGroupCannotSupplyAnotherPartitionsCommit() {
    groupOffsets.put("matching-partition-consumer-0",
        Map.of(new TopicPartition(COMMANDS, 1), new OffsetAndMetadata(3)));

    assertThatThrownBy(() -> client().observe()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another partition");
  }

  @Test
  void negativeMatchingCommitCannotBecomeMissingProgressEvenForAnEmptyLog() {
    commandEndOffset = 0;
    final OffsetAndMetadata invalidCommit = mock(OffsetAndMetadata.class);
    when(invalidCommit.offset()).thenReturn(-1L);
    groupOffsets.put("matching-partition-consumer-0",
        Map.of(new TopicPartition(COMMANDS, 0), invalidCommit));

    assertThatThrownBy(() -> client().observe()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed offset is negative");
  }

  private AdminKafkaAdmissionClient client() {
    return new AdminKafkaAdmissionClient(admin, COMMANDS, EVENTS,
        new GatewayLiveObservationProperties.ConsumerGroups("persistence", "account", "quickfix"),
        Duration.ofSeconds(1), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static TopicDescription topic(String name) {
    final List<TopicPartitionInfo> partitions = IntStream.range(0, 15)
        .mapToObj(partition -> new TopicPartitionInfo(partition, Node.noNode(), List.of(), List.of()))
        .toList();
    return new TopicDescription(name, false, partitions);
  }
}
