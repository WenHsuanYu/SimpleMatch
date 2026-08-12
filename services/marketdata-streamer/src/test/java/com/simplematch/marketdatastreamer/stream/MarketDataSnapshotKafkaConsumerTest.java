package com.simplematch.marketdatastreamer.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.simplematch.contracts.marketdata.runtime.v1.MarketDataSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class MarketDataSnapshotKafkaConsumerTest {
  @Test
  void publishesCompleteSnapshotsBeforeAcknowledgingTheSourceRecord() {
    final List<MarketDataSnapshot> published = new ArrayList<>();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final MarketDataSnapshot snapshot = MarketDataSnapshot.newBuilder().setIsSnapshot(true).build();

    new MarketDataSnapshotKafkaConsumer(published::add)
        .accept(
            new ConsumerRecord<>(
                "marketdata.events", 0, 4L, "XTAI:2330", snapshot.toByteArray()),
            acknowledgment);

    assertThat(published).containsExactly(snapshot);
    verify(acknowledgment).acknowledge();
  }

  @Test
  void acknowledgesInvalidSnapshotsWithoutCallingThePublisher() {
    final List<MarketDataSnapshot> published = new ArrayList<>();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);

    new MarketDataSnapshotKafkaConsumer(published::add)
        .accept(
            new ConsumerRecord<>("marketdata.events", 0, 4L, "XTAI:2330", new byte[] {1, 2, 3}),
            acknowledgment);

    assertThat(published).isEmpty();
    verify(acknowledgment).acknowledge();
  }
}
