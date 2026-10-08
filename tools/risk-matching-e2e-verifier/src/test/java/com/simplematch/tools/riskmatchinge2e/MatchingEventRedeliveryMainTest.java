package com.simplematch.tools.riskmatchinge2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MatchingEventRedeliveryMainTest {
  private static final TopicPartition PARTITION = new TopicPartition("matching.events", 4);
  private static final String COMMAND = "0198a000-0000-7000-8000-000000000003";
  private static final String ORDER = "0198a000-0000-7000-8000-000000000004";

  @Test
  void republishesExactBytesAndRequiresIndependentReadAtTheNewPhysicalOffset() throws Exception {
    final Consumer<byte[], byte[]> consumer = mock();
    final Producer<byte[], byte[]> producer = mock();
    final var original = record(1);
    when(consumer.poll(any(Duration.class))).thenReturn(records(original)).thenReturn(records(record(2)));
    when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(metadata(2)));

    final var evidence = MatchingEventRedeliveryMain.redeliver(arguments(), consumer, producer);
    final ArgumentCaptor<ProducerRecord<byte[], byte[]>> sent = ArgumentCaptor.captor();
    verify(producer).send(sent.capture());
    assertThat(sent.getValue().partition()).isEqualTo(4);
    assertThat(sent.getValue().key()).containsExactly(original.key());
    assertThat(sent.getValue().value()).containsExactly(original.value());
    assertThat(evidence).containsEntry("originalOffset", 1L).containsEntry("observedOffset", 2L)
        .containsEntry("keyBytesEqual", true).containsEntry("valueBytesEqual", true)
        .doesNotContainKeys("rawPayload", "payloadBase64");
  }

  @Test
  void wrongOriginalOffsetOrCommandFailsBeforePublishing() {
    final Consumer<byte[], byte[]> consumer = mock();
    final Producer<byte[], byte[]> producer = mock();
    when(consumer.poll(any(Duration.class))).thenReturn(records(record(2)));
    assertThatThrownBy(() -> MatchingEventRedeliveryMain.redeliver(arguments(), consumer, producer))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(producer);
  }

  @Test
  void producerAcknowledgementWithoutTheMatchingReadCannotPass() {
    final Consumer<byte[], byte[]> consumer = mock();
    final Producer<byte[], byte[]> producer = mock();
    when(consumer.poll(any(Duration.class))).thenReturn(records(record(1))).thenReturn(records(record(3)));
    when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(metadata(2)));
    assertThatThrownBy(() -> MatchingEventRedeliveryMain.redeliver(arguments(), consumer, producer))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sameEventIdentityWithConflictingBytesOrKeyFails() {
    final Consumer<byte[], byte[]> consumer = mock();
    final Producer<byte[], byte[]> producer = mock();
    final var conflicting = new ConsumerRecord<byte[], byte[]>("matching.events", 4, 2,
        "different-key".getBytes(StandardCharsets.UTF_8), record(1).value());
    when(consumer.poll(any(Duration.class))).thenReturn(records(record(1))).thenReturn(records(conflicting));
    when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(metadata(2)));
    assertThatThrownBy(() -> MatchingEventRedeliveryMain.redeliver(arguments(), consumer, producer))
        .isInstanceOf(IllegalStateException.class);
  }

  private MatchingEventObservationMain.ObservationArguments arguments() {
    return new MatchingEventObservationMain.ObservationArguments("kafka:9092", "matching.events", 4,
        1, COMMAND, ORDER, Duration.ofSeconds(1), Path.of("build/evidence"), null);
  }

  private ConsumerRecord<byte[], byte[]> record(long offset) {
    final var event = MatchingEventObservationMainTest.cancelledEvent(
        "2026-08-27-regular", 4, UUID.fromString(COMMAND), ORDER);
    return new ConsumerRecord<>("matching.events", 4, offset,
        event.getEventId().toByteArray(), event.toByteArray());
  }

  private ConsumerRecords<byte[], byte[]> records(ConsumerRecord<byte[], byte[]> record) {
    return new ConsumerRecords<>(Map.of(PARTITION, List.of(record)), Map.of());
  }

  private RecordMetadata metadata(long offset) {
    return new RecordMetadata(PARTITION, offset, 0, 1234L, 32, 200);
  }
}
