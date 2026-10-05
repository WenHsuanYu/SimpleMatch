package com.simplematch.config.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CriticalConsumerOperationalStatusProviderTest {
  private static final Instant NOW = Instant.parse("2026-10-05T01:00:00Z");

  @Test
  void reportsDurableOpenQuarantineAtRequestTime() {
    final MutableQuarantineStore store = new MutableQuarantineStore();
    final CriticalConsumerOperationalStatusProvider provider =
        new CriticalConsumerOperationalStatusProvider(
            "persistence-matching-events", store, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(provider.current(Map.of(0, 4L), Map.of(0, 12L)))
        .isEqualTo(
            new CriticalConsumerOperationalStatus(
                false, Map.of(0, 4L), Map.of(0, 12L), NOW));

    store.positions = List.of(new DeliveryPosition("matching.events", 3, 17));

    assertThat(provider.current(Map.of(0, 4L), Map.of()))
        .isEqualTo(
            new CriticalConsumerOperationalStatus(true, Map.of(0, 4L), Map.of(), NOW));
  }

  private static final class MutableQuarantineStore implements QuarantineStore {
    private List<DeliveryPosition> positions = List.of();

    @Override
    public void save(QuarantineEvidence evidence) {}

    @Override
    public List<DeliveryPosition> loadOpenPositions(String consumerName) {
      return positions;
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {}
  }
}
