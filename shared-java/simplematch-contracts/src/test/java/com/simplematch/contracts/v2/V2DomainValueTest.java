package com.simplematch.contracts.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V2DomainValueTest {
  @DisplayName("TWD fixed-point values use exactly four decimal places")
  @Test
  void convertsTwdPriceToTenThousandthUnits() {
    assertEquals(1_234_567L, TwdPrice.ofDecimal("123.4567").units());
  }

  @DisplayName("fixed-point values reject precision beyond one ten-thousandth TWD")
  @Test
  void rejectsTwdPriceWithUnsupportedPrecision() {
    assertThrows(IllegalArgumentException.class, () -> TwdPrice.ofDecimal("123.45678"));
  }

  @DisplayName("fixed-point values reject missing decimals with a domain validation error")
  @Test
  void rejectsMissingTwdPrice() {
    assertThrows(DomainValidationException.class, () -> TwdPrice.ofDecimal(null));
  }

  @DisplayName("trading days reject missing values with a domain validation error")
  @Test
  void rejectsMissingTradingDay() {
    assertThrows(DomainValidationException.class, () -> TradingDay.parse(null));
  }

  @DisplayName("share quantity and venue values enforce the phase-one market model")
  @Test
  void rejectsNonPositiveQuantityAndUnsupportedVenue() {
    assertThrows(IllegalArgumentException.class, () -> new ShareQuantity(0));
    assertThrows(IllegalArgumentException.class, () -> VenueMic.parse("XNYS"));
  }
}
