package com.simplematch.accountservice.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.CriticalConsumerOperationalStatus;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountCriticalConsumerOperationalStatusControllerTest {
  @Test
  void exposesServiceOwnedDurableQuarantineState() {
    final Instant now = Instant.parse("2026-10-05T01:00:00Z");
    final QuarantineStore store = new OneOpenQuarantineStore();
    final AccountFinalMatchingEventStatus consumerStatus =
        new AccountFinalMatchingEventStatus(Clock.fixed(now, ZoneOffset.UTC));
    consumerStatus.recordCommitted(2, 10);
    final var controller =
        new AccountCriticalConsumerOperationalStatusController(
            new CriticalConsumerOperationalStatusProvider(
                "account-final-matching-events", store, Clock.fixed(now, ZoneOffset.UTC)),
            consumerStatus);

    assertThat(controller.status())
        .isEqualTo(
            new CriticalConsumerOperationalStatus(true, Map.of(2, 11L), Map.of(), now));
  }

  private static final class OneOpenQuarantineStore implements QuarantineStore {
    @Override
    public void save(QuarantineEvidence evidence) {}

    @Override
    public List<DeliveryPosition> loadOpenPositions(String consumerName) {
      return List.of(new DeliveryPosition("matching.events", 2, 11));
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {}
  }
}
