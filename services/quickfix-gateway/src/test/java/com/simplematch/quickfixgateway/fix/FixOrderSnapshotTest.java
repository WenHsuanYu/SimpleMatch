package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FixOrderSnapshotTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "0", "-1", "1.5", "9223372036854775808"})
  void durableRecipientRejectsAbsentFractionalOrOutOfRangeShares(String quantity) {
    final FixOrderSnapshot order = new FixOrderSnapshot(
        new FixOrderSnapshot.OrderId("O-C1"), new FixOrderSnapshot.ClientOrderId("C1"),
        new FixOrderSnapshot.Symbol("2330"), Side.SIDE_BUY,
        new FixOrderSnapshot.Quantity(quantity));
    assertThatThrownBy(() -> new FinalFixDeliveryRecipient(
        java.util.UUID.randomUUID(), new quickfix.SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT"), order))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("FIX report context rejects an absent client order identity")
  @Test
  void rejectsBlankClientOrderIdentity() {
    assertThatThrownBy(() -> new FixOrderSnapshot.ClientOrderId(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("cl_ord_id must not be blank");
  }

  @DisplayName("FIX report context rejects a missing order identity")
  @Test
  void rejectsMissingOrderIdentity() {
    assertThatThrownBy(
            () ->
                new FixOrderSnapshot(
                    null,
                    new FixOrderSnapshot.ClientOrderId("C1"),
                    new FixOrderSnapshot.Symbol("AAPL"),
                    Side.SIDE_BUY,
                    new FixOrderSnapshot.Quantity("10")))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("orderId");
  }
}
