package com.simplematch.quickfixgateway.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradingSessionCloseCoordinatorTest {
  private static final Instant NOW = Instant.parse("2026-08-11T05:31:00Z");

  @Test
  void retriesTemporaryFailureOnBoundedSchedule() {
    final RetryOnceClosePort closePort = new RetryOnceClosePort();
    final TradingSessionCloseCoordinator coordinator = new TradingSessionCloseCoordinator(closePort);
    final TradingSystemStatus status = status("2026-08-11-XTAI");

    coordinator.request(status, NOW);
    coordinator.request(status, NOW.plusSeconds(1));
    coordinator.request(status, NOW.plusSeconds(2));

    assertThat(closePort.attempts()).isEqualTo(2);
    assertThat(closePort.sessionIds()).containsExactly("2026-08-11-XTAI");
  }

  @Test
  void pinsTheFirstTradingSessionIdentityAcrossRetries() {
    final RetryOnceClosePort closePort = new RetryOnceClosePort();
    final TradingSessionCloseCoordinator coordinator = new TradingSessionCloseCoordinator(closePort);

    coordinator.request(status("2026-08-11-XTAI"), NOW);
    coordinator.request(status("2026-08-12-XTAI"), NOW.plusSeconds(2));

    assertThat(closePort.sessionIds()).containsExactly("2026-08-11-XTAI");
  }

  @Test
  void stopsAfterBoundedTemporaryFailures() {
    final AlwaysRetryableClosePort closePort = new AlwaysRetryableClosePort();
    final TradingSessionCloseCoordinator coordinator = new TradingSessionCloseCoordinator(closePort);
    final TradingSystemStatus status = status("2026-08-11-XTAI");

    for (int attempt = 0; attempt < 8; attempt++) {
      coordinator.request(status, NOW.plusSeconds(attempt * 2L));
    }

    assertThat(closePort.attempts()).isEqualTo(5);
  }

  @Test
  void permanentFailureStopsFurtherAttempts() {
    final PermanentlyFailingClosePort closePort = new PermanentlyFailingClosePort();
    final TradingSessionCloseCoordinator coordinator = new TradingSessionCloseCoordinator(closePort);
    final TradingSystemStatus status = status("2026-08-11-XTAI");

    coordinator.request(status, NOW);
    coordinator.request(status, NOW.plusSeconds(30));

    assertThat(closePort.attempts()).isEqualTo(1);
  }

  private static TradingSystemStatus status(String tradingSessionId) {
    final TradingIdentity identity =
        new TradingIdentity(
            tradingSessionId,
            "market-reference-2026-08-11",
            "e4a1bdc9",
            1,
            1,
            "lmax-matching-v1",
            "sha256:matching-image-20260811");
    return new TradingSystemStatus(
        TradingReadiness.OPEN_ELIGIBLE,
        Optional.of(identity),
        List.of(),
        List.of(),
        NOW);
  }

  private static final class RetryOnceClosePort implements TradingSessionClosePort {
    private int attempts;
    private final List<String> sessionIds = new ArrayList<>();

    @Override
    public void close(String tradingSessionId) {
      attempts++;
      if (attempts == 1) {
        throw new RetryableTradingSessionCloseException(
            "temporary transport failure", new IllegalStateException("unavailable"));
      }
      sessionIds.add(tradingSessionId);
    }

    int attempts() {
      return attempts;
    }

    List<String> sessionIds() {
      return List.copyOf(sessionIds);
    }
  }

  private static final class AlwaysRetryableClosePort implements TradingSessionClosePort {
    private int attempts;

    @Override
    public void close(String tradingSessionId) {
      attempts++;
      throw new RetryableTradingSessionCloseException(
          "temporary transport failure", new IllegalStateException("unavailable"));
    }

    int attempts() {
      return attempts;
    }
  }

  private static final class PermanentlyFailingClosePort implements TradingSessionClosePort {
    private int attempts;

    @Override
    public void close(String tradingSessionId) {
      attempts++;
      throw new IllegalStateException("invalid close request");
    }

    int attempts() {
      return attempts;
    }
  }
}
