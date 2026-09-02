package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the WAL preserves the full external FIX OrderID identity contract. */
class WalOrderReferenceTest {
  @Test
  void acceptsOrderIdDerivedFromMaximumClientOrderId() {
    final String clientOrderId = "C".repeat(64);
    final String orderId = "O-" + clientOrderId;

    final WalOrderReference reference =
        new WalOrderReference(orderId, clientOrderId, "", "account");

    assertThat(reference.orderId()).isEqualTo(orderId).hasSize(66);
  }
}
