package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import quickfix.Message;

/** Checks connection evidence recorded from actual FIX application callbacks. */
class FixRecoveryProbeTest {
  @Test
  void recordsAReconnectOnlyAfterASecondLogonAndAnObservedLogout() {
    final FixRecoveryProbe probe = new FixRecoveryProbe();
    probe.onLogon();
    assertThat(probe.connectionEvidence().path("logonCount").asInt()).isEqualTo(1);
    assertThat(probe.connectionEvidence().path("reconnectedAtEpochMs").asLong()).isZero();

    probe.onLogout();
    probe.onLogon();
    final var evidence = probe.connectionEvidence();
    assertThat(evidence.path("logonCount").asInt()).isEqualTo(2);
    assertThat(evidence.path("logoutCount").asInt()).isEqualTo(1);
    assertThat(evidence.path("logoutAtEpochMs").asLong()).isPositive();
    assertThat(evidence.path("reconnectedAtEpochMs").asLong())
        .isGreaterThanOrEqualTo(evidence.path("logoutAtEpochMs").asLong());
  }

  @Test
  void hashesTheWholeOrderBodyWithoutConflatingItWithSessionHeaders() throws Exception {
    final Message original = new Message();
    original.setString(11, "original-order");
    original.setInt(38, 1000);
    original.getHeader().setInt(34, 2);
    final Message retry = (Message) original.clone();
    retry.getHeader().setInt(34, 9);

    assertThat(FixRecoveryProbe.bodyDigest(retry)).isEqualTo(FixRecoveryProbe.bodyDigest(original));
    retry.setInt(38, 2000);
    assertThat(FixRecoveryProbe.bodyDigest(retry)).isNotEqualTo(FixRecoveryProbe.bodyDigest(original));
  }
}
