package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayAdmissionGateTest {
  @Test
  void fiveStateLifecycleKeepsCancellationsOpenOnlyDuringNewOrderPause() {
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();

    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.PRE_OPEN);
    assertThat(gate.allowsNewOrders()).isFalse();
    assertThat(gate.allowsCancellations()).isFalse();
    assertThat(gate.newOrderFailure().reasonCode()).isEqualTo("MARKET_PRE_OPEN");

    assertThat(gate.open()).isTrue();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.OPEN);
    assertThat(gate.allowsNewOrders()).isTrue();
    assertThat(gate.allowsCancellations()).isTrue();

    gate.pauseNewOrders("MATCHING_PARTITION_RECOVERING");
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.NEW_ORDERS_PAUSED);
    assertThat(gate.allowsNewOrders()).isFalse();
    assertThat(gate.allowsCancellations()).isTrue();
    assertThat(gate.newOrderFailure().reasonCode()).isEqualTo("NEW_ORDERS_PAUSED");

    gate.interruptMarket();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.MARKET_INTERRUPTED);
    assertThat(gate.allowsCancellations()).isFalse();
    assertThat(gate.cancelFailure().reasonCode()).isEqualTo("MARKET_INTERRUPTED");

    assertThat(gate.open()).isTrue();
    gate.closeDay();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(gate.open()).isFalse();
    assertThat(gate.newOrderFailure().reasonCode()).isEqualTo("MARKET_CLOSED");
  }
}
