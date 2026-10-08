package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Symbol;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

class FixCancelProbeTest {
  @Test
  void sendsNewCancelIdentityForTheOriginalOrderAndRetainsTerminalReportIdentity()
      throws Exception {
    final var request = FixCancelProbe.cancelRequest(order(), "CAN-168-001");
    assertThat(request.getString(ClOrdID.FIELD)).isEqualTo("CAN-168-001");
    assertThat(request.getString(OrigClOrdID.FIELD)).isEqualTo("REST-168-001");
    assertThat(request.getString(Account.FIELD)).isEqualTo("0198a000-0000-7000-8000-000000000001");
    assertThat(request.getString(OrderQty.FIELD)).isEqualTo("1000");
    assertThat(request.getString(Symbol.FIELD)).isEqualTo("1101");
    final var evidence = FixCancelProbe.evidence(request, report('4'), 1234L);
    assertThat(evidence.path("clOrdId").asText()).isEqualTo("CAN-168-001");
    assertThat(evidence.path("reportClOrdId").asText()).isEqualTo("REST-168-001");
    assertThat(evidence.path("orderId").asText()).isEqualTo("O-REST-168-001");
    assertThat(evidence.has("rawFix")).isFalse();
  }

  @Test
  void pendingCancelOrUnrelatedOrderCannotBecomeSuccessfulTerminalEvidence() throws Exception {
    final var request = FixCancelProbe.cancelRequest(order(), "CAN-168-001");
    assertThatThrownBy(() -> FixCancelProbe.evidence(request, report('6'), 1234L))
        .isInstanceOf(AssertionError.class);
    final var unrelated = report('4');
    unrelated.setString(ClOrdID.FIELD, "another-order");
    assertThatThrownBy(() -> FixCancelProbe.evidence(request, unrelated, 1234L))
        .isInstanceOf(AssertionError.class);
  }

  private NewOrderSingle order() {
    final var order = new NewOrderSingle();
    order.setString(ClOrdID.FIELD, "REST-168-001");
    order.setString(Account.FIELD, "0198a000-0000-7000-8000-000000000001");
    order.setString(Symbol.FIELD, "1101");
    order.setString(OrderQty.FIELD, "1000");
    order.setChar(quickfix.field.Side.FIELD, '1');
    return order;
  }

  private ExecutionReport report(char status) {
    final var report = new ExecutionReport();
    report.setString(ClOrdID.FIELD, "REST-168-001");
    report.setString(OrderID.FIELD, "O-REST-168-001");
    report.setChar(ExecType.FIELD, status);
    report.setChar(OrdStatus.FIELD, status);
    return report;
  }
}
