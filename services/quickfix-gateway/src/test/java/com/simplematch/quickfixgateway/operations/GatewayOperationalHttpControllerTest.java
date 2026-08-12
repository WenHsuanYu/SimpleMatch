package com.simplematch.quickfixgateway.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.quickfixgateway.config.QuickFixGatewayOperationsProperties;
import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GatewayOperationalHttpControllerTest {
  private static final Instant NOW = Instant.parse("2026-08-12T01:00:00Z");
  private static final String TOKEN = "operator-token";

  @Test
  void rejectsMissingOrIncorrectOperatorToken() {
    final GatewayOperationalHttpController controller = controller();

    assertThatThrownBy(() -> controller.status(null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401 UNAUTHORIZED");
    assertThatThrownBy(() -> controller.status("wrong"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401 UNAUTHORIZED");
  }

  @Test
  void exposesStatusAndFixedCommandsThroughTheAuthenticatedBoundary() {
    final GatewayOperationalHttpController controller = controller();

    assertThat(controller.status(TOKEN).operation()).isEqualTo(GatewayOperation.STATUS);
    final GatewayOperationResult interrupted =
        controller.execute(
            "interrupt-market",
            new GatewayOperationalHttpController.OperatorCommandRequest("ops", "integrity"),
            TOKEN);

    assertThat(interrupted.operation()).isEqualTo(GatewayOperation.INTERRUPT_MARKET);
    assertThat(interrupted.accepted()).isTrue();
    assertThat(interrupted.gateState()).isEqualTo(GatewayAdmissionGate.State.MARKET_INTERRUPTED);
  }

  @Test
  void acceptsOnlyTheFixedOperationVocabulary() {
    final GatewayOperationalHttpController controller = controller();

    assertThatThrownBy(
            () ->
                controller.execute(
                    "restart-process",
                    new GatewayOperationalHttpController.OperatorCommandRequest("ops", "test"),
                    TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400 BAD_REQUEST");
  }

  private static GatewayOperationalHttpController controller() {
    final GatewayOperationalController domainController =
        new GatewayOperationalController(
            new GatewayAdmissionGate(),
            new TradingSystemStatusEvaluator(
                15, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)),
            new GatewayOperationalPolicy(
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                ZoneId.of("Asia/Taipei"),
                java.time.LocalTime.of(13, 30),
                true),
            new TestAuditStore(),
            Clock.fixed(NOW, ZoneId.of("UTC")));
    final QuickFixGatewayOperationsProperties properties =
        new QuickFixGatewayOperationsProperties(
            3,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            "Asia/Taipei",
            "13:30",
            true,
            1000,
            true,
            true,
            TOKEN);
    return new GatewayOperationalHttpController(
        new GatewayOperationalCommandHandler(domainController), domainController, properties);
  }

  private static final class TestAuditStore implements GatewayOperationAuditStore {
    private final List<GatewayOperationAudit> audits = new ArrayList<>();

    @Override
    public void append(GatewayOperationAudit audit) {
      audits.add(audit);
    }
  }
}
