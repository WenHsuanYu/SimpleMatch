package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.test.FixMessageSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;

class FixMessageMapperGoldenTest {
  private final FixMessageMapper fixMessageMapper =
      new FixMessageMapper(Clock.fixed(Instant.parse("2024-03-27T08:09:10.123Z"), ZoneOffset.UTC));

  // Verify that the Pending New Execution Report fields match the golden snapshot.
  // Scenario: build the message with a fixed clock so the FIX field order and contents remain
  // stable.
  @DisplayName("Pending New reports match the golden snapshot")
  @Test
  void pendingNewExecutionReportMatchesGoldenSnapshot() {
    final String snapshot =
        FixMessageSnapshot.snapshot(
            fixMessageMapper.buildPendingNew(
                "O-C1",
                "E1",
                "C1",
                "AAPL",
                Side.SIDE_BUY,
                "10",
                Instant.parse("2024-03-27T08:09:10.123Z")),
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
            "35=8|37=O-C1|17=E1|150=A|39=A|54=1|151=10|14=0|6=0|11=C1|55=AAPL|60=2024-03-27T08:09:10.123Z");
  }

  // Verify that the Cancel Reject message fields match the golden snapshot.
  // Scenario: provide the full state and a CANCEL_REJECTED event, then confirm the mapper output
  // remains stable.
  @DisplayName("Cancel Reject messages match the golden snapshot")
  @Test
  void orderCancelRejectMatchesGoldenSnapshot() {
    final OrderSessionState state =
        new OrderSessionState(
            new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH"),
            "O-C1",
            "ACC-1",
            "C1",
            "AAPL",
            Side.SIDE_BUY,
            "10",
            'A');

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
            .setSide(Side.SIDE_BUY)
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
}
