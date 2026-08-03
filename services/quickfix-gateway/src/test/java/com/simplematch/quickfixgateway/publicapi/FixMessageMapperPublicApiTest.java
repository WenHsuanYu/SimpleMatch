package com.simplematch.quickfixgateway.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.EventMetadata;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixOrderSnapshot;
import com.simplematch.quickfixgateway.fix.OrderSessionLifecycle;
import com.simplematch.quickfixgateway.fix.OrderSessionState;
import java.time.Clock;
import quickfix.FieldNotFound;
import quickfix.SessionID;
import quickfix.field.OrdStatus;

/** Verifies that the public FIX rendering seam exposes only publicly usable session values. */
class FixMessageMapperPublicApiTest {
  @org.junit.jupiter.api.Test
  void externalCallerCanRenderCancelRejectFromPublicSessionState() throws FieldNotFound {
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
            .setExecutionType(ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED)
            .setSide(Side.SIDE_BUY)
            .setCancelClOrdId("CXL-1")
            .setOrigClOrdId("C1")
            .build();

    final char ordStatus =
        new FixMessageMapper(Clock.systemUTC())
            .buildOrderCancelReject(event, state)
            .getChar(OrdStatus.FIELD);

    assertThat(ordStatus).isEqualTo('A');
  }
}
