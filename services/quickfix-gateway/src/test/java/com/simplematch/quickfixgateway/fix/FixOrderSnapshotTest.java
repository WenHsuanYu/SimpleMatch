package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.common.v2.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixOrderSnapshotTest {
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
