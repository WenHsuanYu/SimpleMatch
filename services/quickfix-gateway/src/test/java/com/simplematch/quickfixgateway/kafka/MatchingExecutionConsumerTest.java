package com.simplematch.quickfixgateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
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
    // Verify that a tracked execution event is converted into the corresponding FIX Execution Report and sent back to the original session.
    // Scenario: the registry already contains an accepted new order; receiving EXECUTION_TYPE_NEW should generate the standard report.
    @DisplayName("tracked order executions are converted into Execution Reports")
  @Test
  void executionEventBuildsExecutionReportForTrackedOrder() throws Exception {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
        new WalRecord(
            "v1",
            "cmd-1",
            1L,
            "quickfix-gateway",
            "FIX.4.4:CLIENT1->SIMPLEMATCH",
            "D",
            "O-C1",
            "C1",
            "",
            "ACC-1",
            "AAPL",
            Side.SIDE_BUY,
            "10",
            "100",
            OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_ROD,
            CommandType.COMMAND_TYPE_NEW,
            "raw"),
        'A');

    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = new MatchingExecutionConsumer(
        registry,
        registry,
        new FixMessageMapper(Clock.fixed(Instant.parse("2024-03-27T08:09:10.123Z"), ZoneOffset.UTC)),
        sender);

    final ExecutionEvent event = ExecutionEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId("evt-1")
            .setCreatedAtUnixMs(1711526950123L)
            .setSourceService("matching-engine")
            .build())
        .setExecId("E2")
        .setOrderId("O-C1")
        .setSymbol("AAPL")
        .setExecutionType(ExecutionType.EXECUTION_TYPE_NEW)
        .setClOrdId("C1")
        .setSide(Side.SIDE_BUY)
        .setLeavesQty("10")
        .setCumQty("0")
        .setAveragePx("0")
        .build();

    consumer.onExecution(event.toByteArray());

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(FixMessageSnapshot.snapshot(messageCaptor.getValue(), 35, 37, 17, 150, 39, 54, 151, 14, 6, 11, 55, 60))
        .isEqualTo("35=8|37=O-C1|17=E2|150=0|39=0|54=1|151=10|14=0|6=0|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");
  }

    // Verify that a rejected cancel execution is converted into a FIX Order Cancel Reject.
    // Scenario: receive a CANCEL_REJECTED event and, when the registry can resolve the original order state, send a 35=9 message.
    @DisplayName("cancel failure events are converted into Order Cancel Reject")
  @Test
  void cancelRejectedExecutionBuildsOrderCancelReject() throws Exception {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final SessionID sessionId = new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH");
    registry.registerAcceptedOrder(
        sessionId,
        new WalRecord(
            "v1",
            "cmd-1",
            1L,
            "quickfix-gateway",
            "FIX.4.4:CLIENT1->SIMPLEMATCH",
            "D",
            "O-C1",
            "C1",
            "",
            "ACC-1",
            "AAPL",
            Side.SIDE_BUY,
            "10",
            "100",
            OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_ROD,
            CommandType.COMMAND_TYPE_NEW,
            "raw"),
        'A');

    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = new MatchingExecutionConsumer(
        registry,
        registry,
        new FixMessageMapper(Clock.fixed(Instant.parse("2024-03-27T08:09:10.123Z"), ZoneOffset.UTC)),
        sender);

    final ExecutionEvent event = ExecutionEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId("evt-2")
            .setCreatedAtUnixMs(1711526950123L)
            .setSourceService("matching-engine")
            .build())
        .setExecId("E-CXL-1")
        .setOrderId("O-C1")
        .setSymbol("AAPL")
        .setExecutionType(ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED)
        .setClOrdId("C1")
        .setOrigClOrdId("C1")
        .setSide(Side.SIDE_BUY)
        .setCancelClOrdId("CXL-1")
        .setText("too late to cancel")
        .build();

    consumer.onExecution(event.toByteArray());

    final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(sender).send(any(SessionID.class), messageCaptor.capture());
    assertThat(FixMessageSnapshot.snapshot(messageCaptor.getValue(), 35, 37, 11, 41, 39, 434, 102, 58))
        .isEqualTo("35=9|37=O-C1|11=CXL-1|41=C1|39=A|434=1|102=0|58=too late to cancel");
  }

    // Verify that an execution event missing required enrichment fails immediately to avoid sending an incomplete FIX message.
    // Scenario: build an execution event with only the base fields, then confirm the consumer throws and does not call the sender.
    @DisplayName("execution events missing required enrichment fail fast")
  @Test
  void executionEventWithoutRequiredEnrichmentFailsFast() throws Exception {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
        new WalRecord(
            "v1",
            "cmd-1",
            1L,
            "quickfix-gateway",
            "FIX.4.4:CLIENT1->SIMPLEMATCH",
            "D",
            "O-C1",
            "C1",
            "",
            "ACC-1",
            "AAPL",
            Side.SIDE_BUY,
            "10",
            "100",
            OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_ROD,
            CommandType.COMMAND_TYPE_NEW,
            "raw"),
        'A');

    final FixSessionMessageSender sender = mock(FixSessionMessageSender.class);
    final MatchingExecutionConsumer consumer = new MatchingExecutionConsumer(
        registry,
        registry,
        new FixMessageMapper(Clock.fixed(Instant.parse("2024-03-27T08:09:10.123Z"), ZoneOffset.UTC)),
        sender);

    final ExecutionEvent event = ExecutionEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder()
            .setSchemaVersion("v1")
            .setEventId("evt-3")
            .setCreatedAtUnixMs(1711526950123L)
            .setSourceService("matching-engine")
            .build())
        .setExecId("E3")
        .setOrderId("O-C1")
        .setSymbol("AAPL")
        .setExecutionType(ExecutionType.EXECUTION_TYPE_NEW)
        .build();

    assertThrows(IllegalArgumentException.class, () -> consumer.onExecution(event.toByteArray()));
    verifyNoInteractions(sender);
  }
}