package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.quickfixgateway.risk.RiskOrderIdentityDeriver;
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
      new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1");

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
  void cancelRegistrationRequiresAnAcceptedOrderOwnedByTheSession() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();

    assertThatThrownBy(
            () ->
                registry.registerCancelRequest(
                    new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT2"),
                    cancelRecordWithoutAccount()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("cancel request requires session-owned accepted order");

    assertThat(registry.find("O-C1")).isEmpty();
  }

  @Test
  void sessionScopedCanonicalLookupDoesNotReturnAnotherSessionsOrder() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    final WalRecord accepted = newOrderRecord();
    registry.registerAcceptedOrder(SESSION_ID, accepted, 'A');
    final String canonicalOrderId = new RiskOrderIdentityDeriver().derive(accepted);
    final SessionID otherSession = new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT2");

    assertThat(registry.find(SESSION_ID, canonicalOrderId)).isPresent();
    assertThat(registry.findAdmittedOrder(SESSION_ID, canonicalOrderId)).contains(accepted);
    assertThat(registry.find(otherSession, canonicalOrderId)).isEmpty();
    assertThat(registry.findAdmittedOrder(otherSession, canonicalOrderId)).isEmpty();
  }

  private WalRecord newOrderRecord() {
    return new WalRecord(
        new WalMetadata(WalMetadata.CURRENT_SCHEMA_VERSION, "cmd-1", 1L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT1", "SIMPLEMATCH", "FIX.4.4:SIMPLEMATCH->CLIENT1"),
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
        new WalMetadata(WalMetadata.CURRENT_SCHEMA_VERSION, "cmd-2", 2L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT1", "SIMPLEMATCH", "FIX.4.4:SIMPLEMATCH->CLIENT1"),
        new WalOrderReference("O-C1", "CXL-1", "C1", ""),
        new WalCommand.Cancel(),
        new RawFixMessage("raw-cancel"));
  }
}
