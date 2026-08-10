package com.simplematch.quickfixgateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;

class MatchingExecutionConsumerTest {
  @DisplayName("tracked order executions are converted into Execution Reports")
  @Test
  void executionEventBuildsExecutionReportForTrackedOrder() throws Exception {
    final OrderSessionRegistry registry = trackedRegistry();
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = consumer(registry, sender);

    final ExecutionEvent event = execution("E2", ExecutionType.EXECUTION_TYPE_NEW);

    consumer.onExecution(event.toByteArray());

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(
            FixMessageSnapshot.snapshot(
                messageCaptor.getValue(), 35, 37, 17, 150, 39, 54, 151, 14, 6, 11, 55, 60))
        .isEqualTo(
            "35=8|37=O-C1|17=E2|150=0|39=0|54=1|151=10|14=0|6=0|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");
  }

  @DisplayName("cancel failure events are converted into Order Cancel Reject")
  @Test
  void cancelRejectedExecutionBuildsOrderCancelReject() throws Exception {
    final OrderSessionRegistry registry = trackedRegistry();
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = consumer(registry, sender);

    final ExecutionEvent event =
        ExecutionEvent.newBuilder()
            .setMetadata(metadata("evt-2"))
            .setExecId("E-CXL-1")
            .setOrderId("O-C1")
            .setSymbol("AAPL")
            .setExecutionType(ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED)
            .setClOrdId("C1")
            .setOrigClOrdId("C1")
            .setSide(com.simplematch.contracts.common.v1.Side.SIDE_BUY)
            .setCancelClOrdId("CXL-1")
            .setText("too late to cancel")
            .build();

    consumer.onExecution(event.toByteArray());

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(
            FixMessageSnapshot.snapshot(messageCaptor.getValue(), 35, 37, 11, 41, 39, 434, 102, 58))
        .isEqualTo("35=9|37=O-C1|11=CXL-1|41=C1|39=A|434=1|102=0|58=too late to cancel");
  }

  @DisplayName("execution events missing required enrichment fail fast")
  @Test
  void executionEventWithoutRequiredEnrichmentFailsFast() throws Exception {
    final OrderSessionRegistry registry = trackedRegistry();
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = consumer(registry, sender);

    final ExecutionEvent event =
        ExecutionEvent.newBuilder()
            .setMetadata(metadata("evt-3"))
            .setExecId("E3")
            .setOrderId("O-C1")
            .setSymbol("AAPL")
            .setExecutionType(ExecutionType.EXECUTION_TYPE_NEW)
            .build();

    assertThrows(IllegalArgumentException.class, () -> consumer.onExecution(event.toByteArray()));
    verifyNoInteractions(sender);
  }

  @DisplayName("a failed FIX projection is retried because it is not marked as seen")
  @Test
  void failedProjectionCanBeRetried() throws Exception {
    final OrderSessionRegistry registry = trackedRegistry();
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    doThrow(new IllegalStateException("temporary sender failure"))
        .when(sender)
        .send(any(SessionID.class), any(Message.class));
    final MatchingExecutionConsumer consumer = consumer(registry, sender);
    final ExecutionEvent event = execution("E-RETRY", ExecutionType.EXECUTION_TYPE_NEW);

    assertThrows(IllegalStateException.class, () -> consumer.onExecution(event.toByteArray()));
    reset(sender);

    consumer.onExecution(event.toByteArray());

    verify(sender).send(any(SessionID.class), any(Message.class));
  }

  @DisplayName("a stale execution cannot downgrade a terminal client lifecycle")
  @Test
  void outOfOrderExecutionDoesNotProjectAContradictoryStatus() throws Exception {
    final OrderSessionRegistry registry = trackedRegistry();
    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = consumer(registry, sender);

    consumer.onExecution(execution("E-FILL", ExecutionType.EXECUTION_TYPE_FILL).toByteArray());
    consumer.onExecution(execution("E-LATE-NEW", ExecutionType.EXECUTION_TYPE_NEW).toByteArray());

    verify(sender, times(1)).send(any(SessionID.class), any(Message.class));
    assertThat(registry.find("O-C1").orElseThrow().lifecycle().currentOrdStatus()).isEqualTo('2');
  }

  private MatchingExecutionConsumer consumer(
      OrderSessionRegistry registry, FixSessionMessageSender sender) {
    return new MatchingExecutionConsumer(
        registry,
        registry,
        new FixMessageMapper(
            Clock.fixed(Instant.parse("2024-03-27T08:09:10.123Z"), ZoneOffset.UTC)),
        sender);
  }

  private OrderSessionRegistry trackedRegistry() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
        new WalRecord(
            new WalMetadata("v1", "cmd-1", 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT1", "SIMPLEMATCH"),
            new WalOrderReference("O-C1", "C1", "", "ACC-1"),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "AAPL",
                    Side.SIDE_BUY,
                    "10",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("raw")),
        'A');
    return registry;
  }

  private ExecutionEvent execution(String execId, ExecutionType executionType) {
    return ExecutionEvent.newBuilder()
        .setMetadata(metadata("evt-" + execId))
        .setExecId(execId)
        .setOrderId("O-C1")
        .setSymbol("AAPL")
        .setExecutionType(executionType)
        .setClOrdId("C1")
        .setSide(com.simplematch.contracts.common.v1.Side.SIDE_BUY)
        .setLeavesQty(executionType == ExecutionType.EXECUTION_TYPE_FILL ? "0" : "10")
        .setCumQty(executionType == ExecutionType.EXECUTION_TYPE_FILL ? "10" : "0")
        .setAveragePx(executionType == ExecutionType.EXECUTION_TYPE_FILL ? "100" : "0")
        .build();
  }

  private EventMetadata metadata(String eventId) {
    return EventMetadata.newBuilder()
        .setSchemaVersion("v1")
        .setEventId(eventId)
        .setCreatedAtUnixMs(1711526950123L)
        .setSourceService("matching-engine")
        .build();
  }
}
