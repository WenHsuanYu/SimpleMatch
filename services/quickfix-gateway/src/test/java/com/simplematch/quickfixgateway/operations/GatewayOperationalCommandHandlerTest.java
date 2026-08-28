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

class GatewayOperationalCommandHandlerTest {
  @Test
  void exposesOnlyTheFiveAcceptedOperatorCommands() {
    final GatewayAdmissionGate gate = new GatewayAdmissionGate();
    final GatewayOperationalController controller =
        new GatewayOperationalController(
            gate,
            new TradingSystemStatusEvaluator(
                15, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)),
            new GatewayOperationalPolicy(
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                ZoneId.of("Asia/Taipei"),
                LocalTime.of(13, 30),
                false),
            new InMemoryAuditStore(),
            tradingSessionId -> {},
            Clock.fixed(Instant.parse("2026-08-11T01:00:00Z"), ZoneId.of("UTC")));
    final GatewayOperationalCommandHandler handler =
        new GatewayOperationalCommandHandler(controller);

    final GatewayOperationResult status =
        handler.execute(
            new GatewayOperationalCommand(GatewayOperation.STATUS, "operator-1", "inspect"));
    final GatewayOperationResult pause =
        handler.execute(
            new GatewayOperationalCommand(
                GatewayOperation.PAUSE_NEW_ORDERS, "operator-1", "maintenance"));
    final GatewayOperationResult interrupt =
        handler.execute(
            new GatewayOperationalCommand(
                GatewayOperation.INTERRUPT_MARKET, "operator-1", "integrity investigation"));
    final GatewayOperationResult close =
        handler.execute(
            new GatewayOperationalCommand(GatewayOperation.CLOSE_DAY, "operator-1", "session end"));

    assertThat(status.operation()).isEqualTo(GatewayOperation.STATUS);
    assertThat(pause.operation()).isEqualTo(GatewayOperation.PAUSE_NEW_ORDERS);
    assertThat(interrupt.operation()).isEqualTo(GatewayOperation.INTERRUPT_MARKET);
    assertThat(close.gateState()).isEqualTo(GatewayAdmissionGate.State.CLOSED);
    assertThat(
            handler.execute(
                    new GatewayOperationalCommand(
                        GatewayOperation.OPEN, "operator-1", "late reopen"))
                .accepted())
        .isFalse();
  }

  private static final class InMemoryAuditStore implements GatewayOperationAuditStore {
    private final List<GatewayOperationAudit> entries = new ArrayList<>();

    @Override
    public void append(GatewayOperationAudit audit) {
      entries.add(audit);
    }
  }
}
