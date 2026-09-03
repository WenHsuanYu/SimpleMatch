package com.simplematch.riskservice.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CdcDeliveryPropertiesTest {
  @Test
  @DisplayName("rejects a CDC query timeout above the probe boundary")
  void rejectsQueryTimeoutAboveProbeBoundary() {
    assertThatThrownBy(
            () ->
                new CdcDeliveryProperties(
                    true, false, "risk-cdc-delivery", Duration.ofSeconds(5), Duration.ofSeconds(11)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("queryTimeout must not exceed 10 seconds");
  }

  @Test
  @DisplayName("accepts the maximum CDC query timeout")
  void acceptsMaximumQueryTimeout() {
    new CdcDeliveryProperties(
        true, false, "risk-cdc-delivery", Duration.ofSeconds(5), Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("rejects a sub-millisecond CDC query timeout")
  void rejectsSubMillisecondQueryTimeout() {
    assertThatThrownBy(
            () ->
                new CdcDeliveryProperties(
                    true,
                    false,
                    "risk-cdc-delivery",
                    Duration.ofSeconds(5),
                    Duration.ofNanos(999_999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("queryTimeout must be at least 1 millisecond");
  }
}
