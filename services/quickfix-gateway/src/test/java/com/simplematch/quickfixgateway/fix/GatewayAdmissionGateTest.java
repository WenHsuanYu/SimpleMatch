package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayAdmissionGateTest {
  @Test
  void admissionPauseAndMarketInterruptionExposeStableRejectionReasons() {
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();

    gate.pauseAdmission();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.ADMISSION_PAUSED);
    assertThat(gate.cancelFailure().reasonCode()).isEqualTo("ADMISSION_PAUSED");

    gate.interruptMarket();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.MARKET_INTERRUPTED);
    assertThat(gate.cancelFailure().reasonCode()).isEqualTo("MARKET_INTERRUPTED");

    gate.reopen();
    assertThat(gate.allowsAdmission()).isTrue();
  }
}
