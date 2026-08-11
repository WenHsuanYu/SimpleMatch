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
    final TradingSystemObservation ready = TradingSystemStatusFixtures.readyObservation(clock.instant());

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
    final TradingSystemObservation ready = TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    assertThat(controller.open("operator-1", "open").accepted()).isTrue();

    controller.report(
        TradingSystemStatusFixtures.withMatchingState(ready, 4, OperationalComponentState.NOT_READY));
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
    final TradingSystemObservation ready = TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    controller.open("operator-1", "open");
    clock.advance(Duration.ofSeconds(6));

    assertThat(controller.monitor().readiness()).isEqualTo(TradingReadiness.PAUSE_REQUIRED);
    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.NEW_ORDERS_PAUSED);
  }

  @Test
  void configuredSessionEndClosesTheTradingDayWithoutReopen() {
    final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-08-11T05:29:00Z"));
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final GatewayOperationalController controller = controller(gate, new TestAuditStore(), clock);
    final TradingSystemObservation ready = TradingSystemStatusFixtures.readyObservation(clock.instant());

    controller.report(ready);
    controller.report(ready);
    controller.report(ready);
    controller.open("operator-1", "open");
    clock.advance(Duration.ofMinutes(2));

    controller.monitor();

    assertThat(gate.state()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(controller.open("operator-1", "late open").accepted()).isFalse();
  }

  private GatewayOperationalController controller(
      GatewayAdmissionGate gate, TestAuditStore auditStore, Clock clock) {
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
