package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.DataDictionary;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.OrderQty;
import quickfix.field.OrdStatus;
import quickfix.field.Symbol;
import quickfix.fix44.NewOrderSingle;

class FixMessageMapperGoldenTest {
  private static final Instant FIXED_TIME = Instant.parse("2024-03-27T08:09:10.123Z");

  private final FixMessageMapper fixMessageMapper =
      new FixMessageMapper(Clock.fixed(FIXED_TIME, ZoneOffset.UTC));

  /**
   * Verifies that a Pending New Execution Report remains identical to the golden FIX snapshot.
   * A fixed clock keeps time-dependent fields and the selected FIX field sequence deterministic.
   */
  @DisplayName("Pending New reports match the golden snapshot")
  @Test
  void pendingNewExecutionReportMatchesGoldenSnapshot() {
    final String snapshot =
        FixMessageSnapshot.snapshot(
            fixMessageMapper.buildPendingNew(
                new FixOrderSnapshot(
                    new FixOrderSnapshot.OrderId("O-C1"),
                    new FixOrderSnapshot.ClientOrderId("C1"),
                    new FixOrderSnapshot.Symbol("AAPL"),
                    Side.SIDE_BUY,
                    new FixOrderSnapshot.Quantity("10")),
                new FixExecutionIdentity(
                    new FixExecutionIdentity.ExecutionId("E1"), FIXED_TIME)),
            35,
            37,
            17,
            150,
            39,
            54,
            151,
            14,
            6,
            11,
            55,
            60);

    assertThat(snapshot)
        .isEqualTo(
            "35=8|37=O-C1|17=E1|150=A|39=A|54=1|151=10|14=0|6=0|"
                + "11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");
  }

  /** Verifies that an inbound order rejection remains a valid FIX 4.4 Execution Report. */
  @DisplayName("Inbound order rejections include required execution state")
  @Test
  void inboundOrderRejectionIncludesRequiredExecutionState() throws Exception {
    final Message inboundOrder = new NewOrderSingle();
    inboundOrder.setString(ClOrdID.FIELD, "C1");
    inboundOrder.setString(Symbol.FIELD, "AAPL");
    inboundOrder.setChar(quickfix.field.Side.FIELD, '1');
    inboundOrder.setString(OrderQty.FIELD, "10");

    final String snapshot =
        FixMessageSnapshot.snapshot(
            fixMessageMapper.buildRejectedInboundOrder(
                inboundOrder,
                new FixExecutionIdentity(
                    new FixExecutionIdentity.ExecutionId("RJ-1"), FIXED_TIME),
                "MARKET_CLOSED"),
            35,
            37,
            17,
            150,
            39,
            54,
            151,
            14,
            6,
            38,
            11,
            55,
            60,
            58);

    assertThat(snapshot)
        .isEqualTo(
            "35=8|37=O-C1|17=RJ-1|150=8|39=8|54=1|151=0|14=0|6=0|"
                + "38=10|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z|"
                + "58=MARKET_CLOSED");
  }

  /**
   * Verifies that a cancel-rejected matching event maps to the stable FIX Order Cancel Reject
   * snapshot when the original accepted-order session state is available.
   */
  @DisplayName("Cancel Reject messages match the golden snapshot")
  @Test
  void orderCancelRejectMatchesGoldenSnapshot() {
    final OrderSessionState state =
        new OrderSessionState(
            new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
            "ACC-1",
            new FixOrderSnapshot(
                new FixOrderSnapshot.OrderId("O-C1"),
                new FixOrderSnapshot.ClientOrderId("C1"),
                new FixOrderSnapshot.Symbol("AAPL"),
                Side.SIDE_BUY,
                new FixOrderSnapshot.Quantity("10")),
            new OrderSessionLifecycle('A'));

    final ExecutionEvent event =
        ExecutionEvent.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
                    .setEventId("evt-1")
                    .setCreatedAtUnixMs(1L)
                    .setSourceService("matching-engine")
                    .build())
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

    final String snapshot =
        FixMessageSnapshot.snapshot(
            fixMessageMapper.buildOrderCancelReject(event, state),
            35,
            37,
            11,
            41,
            39,
            434,
            102,
            58);

    assertThat(snapshot)
        .isEqualTo("35=9|37=O-C1|11=CXL-1|41=C1|39=A|434=1|102=0|58=too late to cancel");
  }

  /** Verifies that a full trade uses the FIX 4.4 trade execution type and filled status. */
  @DisplayName("Full trades use the FIX trade execution type and filled status")
  @Test
  void fullTradeUsesFixTradeExecutionTypeAndFilledStatus() throws Exception {
    final ExecutionEvent event =
        ExecutionEvent.newBuilder()
            .setMetadata(
                EventMetadata.newBuilder()
                    .setSchemaVersion("v1")
                    .setEventId("evt-fill")
                    .setCreatedAtUnixMs(FIXED_TIME.toEpochMilli())
                    .setSourceService("matching-engine")
                    .build())
            .setExecId("E-FILL")
            .setOrderId("O-C1")
            .setSymbol("AAPL")
            .setExecutionType(ExecutionType.EXECUTION_TYPE_FILL)
            .setClOrdId("C1")
            .setSide(com.simplematch.contracts.common.v1.Side.SIDE_BUY)
            .setFillQty("10")
            .setFillPx("101.25")
            .setLeavesQty("0")
            .setCumQty("10")
            .setAveragePx("101.25")
            .build();
    final OrderSessionState state =
        new OrderSessionState(
            new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
            "ACC-1",
            new FixOrderSnapshot(
                new FixOrderSnapshot.OrderId("O-C1"),
                new FixOrderSnapshot.ClientOrderId("C1"),
                new FixOrderSnapshot.Symbol("AAPL"),
                Side.SIDE_BUY,
                new FixOrderSnapshot.Quantity("10")),
            new OrderSessionLifecycle('A'));

    final Message report = fixMessageMapper.buildExecutionReport(event, state);

    assertThat(report.getChar(ExecType.FIELD)).isEqualTo('F');
    assertThat(report.getChar(OrdStatus.FIELD)).isEqualTo('2');
  }

  /** Verifies that partial trades use values accepted by the repository FIX 4.4 dictionary. */
  @DisplayName("Partial trades use dictionary-valid trade type and partial status")
  @Test
  void partialTradeUsesDictionaryValidExecutionValues() throws Exception {
    final DataDictionary dictionary =
        new DataDictionary(
            workspaceRoot().resolve("config/quickfix/fix-spec/FIX44.xml").toString());

    final char executionType =
        FixWireValues.mapExecType(ExecutionType.EXECUTION_TYPE_PARTIAL_FILL);
    final char orderStatus =
        FixWireValues.mapOrdStatus(ExecutionType.EXECUTION_TYPE_PARTIAL_FILL);

    assertThat(executionType).isEqualTo('F');
    assertThat(orderStatus).isEqualTo('1');
    assertThat(dictionary.isFieldValue(ExecType.FIELD, String.valueOf(executionType))).isTrue();
    assertThat(dictionary.isFieldValue(OrdStatus.FIELD, String.valueOf(orderStatus))).isTrue();
  }

  private Path workspaceRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))
          || Files.exists(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("workspace root not found");
  }
}
