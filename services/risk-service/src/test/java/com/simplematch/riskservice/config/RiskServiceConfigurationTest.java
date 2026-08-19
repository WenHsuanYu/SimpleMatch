package com.simplematch.riskservice.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.simplematch.riskservice.admission.AdmissionBackpressurePolicy;
import com.simplematch.riskservice.admission.CdcLagBackpressurePolicy;
import com.simplematch.riskservice.admission.CdcLagReader;
import com.simplematch.riskservice.admission.NoopAdmissionBackpressurePolicy;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RiskServiceConfigurationTest {
  private final RiskServiceConfiguration configuration = new RiskServiceConfiguration();
  private final CdcLagReader unusedReader = metricName -> null;
  private final Clock clock = Clock.systemUTC();

  @Test
  void usesNoopPolicyOnlyWhenCdcBackpressureIsExplicitlyDisabled() {
    final AdmissionBackpressurePolicy policy =
        configuration.admissionBackpressurePolicy(unusedReader, clock, properties(false));

    assertInstanceOf(NoopAdmissionBackpressurePolicy.class, policy);
  }

  @Test
  void usesCdcLagPolicyWhenCdcBackpressureIsEnabled() {
    final AdmissionBackpressurePolicy policy =
        configuration.admissionBackpressurePolicy(unusedReader, clock, properties(true));

    assertInstanceOf(CdcLagBackpressurePolicy.class, policy);
  }

  private static RiskServiceProperties properties(boolean enabled) {
    return new RiskServiceProperties(
        new RiskServiceProperties.AdmissionProperties(
            enabled, "matching.commands", 10_000L, Duration.ofSeconds(30)));
  }
}
