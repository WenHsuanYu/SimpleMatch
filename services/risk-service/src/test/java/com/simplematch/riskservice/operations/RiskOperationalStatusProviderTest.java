package com.simplematch.riskservice.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RiskOperationalStatusProviderTest {
  private static final String ARTIFACT_CHECKSUM = "a".repeat(64);
  private static final String MATCHING_IMAGE_DIGEST = "sha256:" + "b".repeat(64);

  @Test
  void publishesTheVerifiedDailyIdentityAtObservationTime() {
    final Instant now = Instant.parse("2026-10-05T01:00:00Z");
    final RiskOperationalIdentity identity =
        new RiskOperationalIdentity(
            "2026-10-05-regular",
            "market-reference-2026-10-05",
            ARTIFACT_CHECKSUM,
            1,
            1,
            "stable-least-loaded-v1",
            MATCHING_IMAGE_DIGEST);

    final RiskOperationalStatusProvider provider =
        new RiskOperationalStatusProvider(identity, Clock.fixed(now, ZoneOffset.UTC));

    assertThat(provider.current()).isEqualTo(new RiskOperationalStatus(identity, now));
  }
}
