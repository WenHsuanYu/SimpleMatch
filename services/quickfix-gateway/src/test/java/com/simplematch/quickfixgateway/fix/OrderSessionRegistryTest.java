package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;

class OrderSessionRegistryTest {
  private static final SessionID SESSION_ID =
      new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH");

  @Test
  void acceptedOrderStateComposesTheExistingFixOrderSnapshot() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();

    registry.registerAcceptedOrder(SESSION_ID, newOrderRecord(), 'A');

    final OrderSessionState state = registry.find("O-C1").orElseThrow();
    assertThat(state.order())
        .isEqualTo(
            new FixOrderSnapshot(
                new FixOrderSnapshot.OrderId("O-C1"),
                new FixOrderSnapshot.ClientOrderId("C1"),
                new FixOrderSnapshot.Symbol("AAPL"),
                Side.SIDE_BUY,
                new FixOrderSnapshot.Quantity("10")));
    assertThat(state.orderId()).isEqualTo("O-C1");
    assertThat(state.clOrdId()).isEqualTo("C1");
    assertThat(state.symbol()).isEqualTo("AAPL");
    assertThat(state.quantity()).isEqualTo("10");
  }

  @Test
  void cancelRegistrationRetainsOrderSnapshotAccountAndCorrelation() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    registry.registerAcceptedOrder(SESSION_ID, newOrderRecord(), 'A');

    registry.registerCancelRequest(SESSION_ID, cancelRecordWithoutAccount());

    final OrderSessionState state = registry.find("O-C1").orElseThrow();
    assertThat(state.accountId()).isEqualTo("ACC-1");
    assertThat(state.order().clientOrderId().value()).isEqualTo("C1");
    assertThat(state.lifecycle().currentOrdStatus()).isEqualTo('A');
    assertThat(state.lifecycle().lastCancelRequest())
        .isEqualTo(new OrderSessionState.CancelRequestState("CXL-1", "C1"));
  }

  @Test
  void cancelRegistrationCreatesTheExistingFallbackWhenOrderStateIsMissing() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();

    registry.registerCancelRequest(SESSION_ID, cancelRecordWithoutAccount());

    final OrderSessionState state = registry.find("O-C1").orElseThrow();
    assertThat(state.sessionId()).isEqualTo(SESSION_ID);
    assertThat(state.accountId()).isEmpty();
    assertThat(state.orderId()).isEqualTo("O-C1");
    assertThat(state.clOrdId()).isEqualTo("C1");
    assertThat(state.symbol()).isEmpty();
    assertThat(state.side()).isEqualTo(Side.SIDE_UNSPECIFIED);
    assertThat(state.quantity()).isEmpty();
    assertThat(state.lifecycle().currentOrdStatus()).isEqualTo('A');
    assertThat(state.lifecycle().lastCancelRequest())
        .isEqualTo(new OrderSessionState.CancelRequestState("CXL-1", "C1"));
  }

  private WalRecord newOrderRecord() {
    return new WalRecord(
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
        new RawFixMessage("raw"));
  }

  private WalRecord cancelRecordWithoutAccount() {
    return new WalRecord(
        new WalMetadata("v1", "cmd-2", 2L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT1", "SIMPLEMATCH"),
        new WalOrderReference("O-C1", "CXL-1", "C1", ""),
        new WalCommand.Cancel(),
        new RawFixMessage("raw-cancel"));
  }
}
