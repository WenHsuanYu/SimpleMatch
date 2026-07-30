package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
