package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verifies that durable publication lag blocks admission at the configured bound. */
class CdcLagBackpressurePolicyTest {
  @Test
  void rejectsWhenLagExceedsBound() {
    final AtomicLong lag = new AtomicLong(11);
    final CdcLagBackpressurePolicy policy = new CdcLagBackpressurePolicy(lag::get, 10);

    assertThatThrownBy(policy::check).isInstanceOf(AdmissionBackpressureException.class);
  }
}
