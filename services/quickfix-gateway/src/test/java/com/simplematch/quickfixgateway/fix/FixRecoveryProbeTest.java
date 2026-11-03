package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import quickfix.Message;

/** Protects replay body identity while allowing FIX session headers to advance. */
class FixRecoveryProbeTest {
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
