package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v2.Side;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.OrderQty;
import quickfix.field.Symbol;
import quickfix.fix44.NewOrderSingle;

class FixMessageMapperGoldenTest {
  private static final Instant FIXED_TIME = Instant.parse("2024-03-27T08:09:10.123Z");

  private final FixMessageMapper fixMessageMapper =
      new FixMessageMapper();

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

}
