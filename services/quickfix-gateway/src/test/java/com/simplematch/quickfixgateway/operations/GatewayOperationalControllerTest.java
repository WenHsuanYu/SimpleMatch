package com.simplematch.quickfixgateway.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayOperationalControllerTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  @Test
  void openingRequiresThreeConsecutiveFullyReadyObservations() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T01:00:00Z"));
    final TestAuditStore auditStore = new TestAuditStore();
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final GatewayOperationalController controller = controller(gate, auditStore, clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    assertThat(controller.open("operator-1", "pre-open review").accepted()).isFalse();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.PRE_OPEN);

    controller.report(ready);
    assertThat(controller.open("operator-1", "pre-open review").accepted()).isTrue();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.OPEN);
    assertThat(auditStore.entries()).isNotEmpty();
  }

  @Test
  void unsafeRecoveryPausesNewOrdersAndRequiresExplicitReopen() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T01:00:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final GatewayOperationalController controller = controller(gate, new TestAuditStore(), clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    assertThat(controller.open("operator-1", "open").accepted()).isTrue();

    controller.report(
        TradingSystemStatusFixtures.withMatchingState(
            ready, 4, OperationalComponentState.NOT_READY));
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.NEW_ORDERS_PAUSED);

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.NEW_ORDERS_PAUSED);
    assertThat(controller.open("operator-1", "recovery verified").accepted()).isTrue();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.OPEN);
  }

  @Test
  void statusSilenceAutomaticallyPausesAnOpenGateway() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T01:00:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final GatewayOperationalController controller = controller(gate, new TestAuditStore(), clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    controller.open("operator-1", "open");
    clock.advance(Duration.ofSeconds(6));

    assertThat(controller.monitor().readiness()).isEqualTo(TradingReadiness.PAUSE_REQUIRED);
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.NEW_ORDERS_PAUSED);
  }

  @Test
  void configuredSessionEndClosesAdmissionAndRequestsRiskClosure() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T05:29:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final RecordingClosePort closePort = new RecordingClosePort();
    final GatewayOperationalController controller =
        controller(gate, new TestAuditStore(), closePort, clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    controller.open("operator-1", "open");
    clock.advance(Duration.ofMinutes(2));

    controller.monitor();

    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(closePort.sessionIds())
        .containsExactly(TradingSystemStatusFixtures.identity().tradingSessionId());
    assertThat(controller.open("operator-1", "late open").accepted()).isFalse();
    assertThat(closePort.sessionIds()).hasSize(1);
  }

  @Test
  void closeWorkflowRetriesAfterTemporaryRiskFailure() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T05:29:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final FailOnceClosePort closePort = new FailOnceClosePort();
    final GatewayOperationalController controller =
        controller(gate, new TestAuditStore(), closePort, clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    controller.open("operator-1", "open");
    clock.advance(Duration.ofMinutes(2));

    controller.monitor();
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(closePort.attempts()).isEqualTo(1);

    controller.monitor();
    controller.monitor();

    assertThat(closePort.attempts()).isEqualTo(2);
    assertThat(closePort.sessionIds())
        .containsExactly(TradingSystemStatusFixtures.identity().tradingSessionId());
  }

  @Test
  void explicitCloseStopsAdmissionBeforeRequestingRiskClosure() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T01:00:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final List<GatewayAdmissionGate.State> observedStates = new ArrayList<>();
    final GatewayOperationalController controller =
        controller(
            gate,
            new TestAuditStore(),
            tradingSessionId -> observedStates.add(gate.state()),
            clock);
    final TradingSystemObservation ready =
        TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.closeDay("operator-1", "session end");

    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(observedStates).containsExactly(GatewayAdmissionGate.State.CLOSED);
  }

  private GatewayOperationalController controller(
      GatewayAdmissionGate gate, TestAuditStore auditStore, Clock clock) {
    return controller(gate, auditStore, tradingSessionId -> {}, clock);
  }

  private GatewayOperationalController controller(
      GatewayAdmissionGate gate,
      TestAuditStore auditStore,
      TradingSessionClosePort closePort,
      Clock clock) {
    return new GatewayOperationalController(
        gate,
        new TradingSystemStatusEvaluator(
            15, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120)),
        new GatewayOperationalPolicy(
            3,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(120),
            TAIPEI,
            LocalTime.of(13, 30),
            true),
        auditStore,
        closePort,
        clock);
  }

  private static final class TestAuditStore implements GatewayOperationAuditStore {
    private final List<GatewayOperationAudit> entries = new ArrayList<>();

    @Override
    public void append(GatewayOperationAudit audit) {
      entries.add(audit);
    }

    List<GatewayOperationAudit> entries() {
      return List.copyOf(entries);
    }
  }

  private static final class RecordingClosePort implements TradingSessionClosePort {
    private final List<String> sessionIds = new ArrayList<>();

    @Override
    public void close(String tradingSessionId) {
      sessionIds.add(tradingSessionId);
    }

    List<String> sessionIds() {
      return List.copyOf(sessionIds);
    }
  }

  private static final class FailOnceClosePort implements TradingSessionClosePort {
    private int attempts;
    private final List<String> sessionIds = new ArrayList<>();

    @Override
    public void close(String tradingSessionId) {
      attempts++;
      if (attempts == 1) {
        throw new IllegalStateException("Risk temporarily unavailable");
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

  private static final class AdjustableClock extends Clock {
    private Instant instant;

    private AdjustableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }
  }
}
