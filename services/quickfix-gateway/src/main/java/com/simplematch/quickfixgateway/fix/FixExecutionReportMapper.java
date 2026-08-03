package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import java.time.Clock;
import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;

/** Renders execution-report messages from accepted, rejected, and matching outcomes. */
final class FixExecutionReportMapper {
  private final Clock clock;

  FixExecutionReportMapper(Clock clock) {
    this.clock = clock;
  }

  ExecutionReport buildPendingNew(FixOrderSnapshot order, FixExecutionIdentity execution) {
    final ExecutionReport report = baseReport(order, execution, 'A', 'A');
    report.setString(
        LeavesQty.FIELD, FixWireValues.normalizeDecimal(order.quantity().value()));
    report.setString(CumQty.FIELD, "0");
    report.setString(AvgPx.FIELD, "0");
    return report;
  }

  ExecutionReport buildRejected(
      FixOrderSnapshot order, FixExecutionIdentity execution, String text) {
    final ExecutionReport report = baseReport(order, execution, '8', '8');
    report.setString(LeavesQty.FIELD, "0");
    report.setString(CumQty.FIELD, "0");
    report.setString(AvgPx.FIELD, "0");
    if (!order.quantity().value().isBlank()) {
      report.setString(
          OrderQty.FIELD, FixWireValues.normalizeDecimal(order.quantity().value()));
    }
    if (text != null && !text.isBlank()) {
      report.setString(Text.FIELD, text);
    }
    return report;
  }

  ExecutionReport buildRejected(
      FixInboundOrderRejection order, FixExecutionIdentity execution, String text) {
    final ExecutionReport report = new ExecutionReport();
    report.setString(OrderID.FIELD, order.orderId());
    report.setString(ExecID.FIELD, execution.executionId().value());
    report.setChar(ExecType.FIELD, '8');
    report.setChar(OrdStatus.FIELD, '8');
    if (!order.clOrdId().isBlank()) {
      report.setString(ClOrdID.FIELD, order.clOrdId());
    }
    if (!order.symbol().isBlank()) {
      report.setString(Symbol.FIELD, order.symbol());
    }
    if (order.side() != Side.SIDE_UNSPECIFIED) {
      report.setChar(quickfix.field.Side.FIELD, FixWireValues.mapSide(order.side()));
    }
    if (!order.quantity().isBlank()) {
      report.setString(OrderQty.FIELD, order.quantity());
    }
    report.setString(TransactTime.FIELD, FixWireValues.format(execution.transactTime()));
    if (text != null && !text.isBlank()) {
      report.setString(Text.FIELD, text);
    }
    return report;
  }

  Message buildExecutionReport(ExecutionEvent executionEvent, OrderSessionState state) {
    final ExecutionReport report = new ExecutionReport();
    report.setString(OrderID.FIELD, executionEvent.getOrderId());
    report.setString(ExecID.FIELD, executionEvent.getExecId());
    report.setChar(ExecType.FIELD, FixWireValues.mapExecType(executionEvent.getExecutionType()));
    report.setChar(OrdStatus.FIELD, FixWireValues.mapOrdStatus(executionEvent.getExecutionType()));
    report.setChar(quickfix.field.Side.FIELD, FixWireValues.mapSide(executionEvent.getSide()));
    report.setString(
        LeavesQty.FIELD,
        FixWireValues.fallbackDecimal(executionEvent.getLeavesQty(), state.quantity()));
    report.setString(CumQty.FIELD, FixWireValues.fallbackDecimal(executionEvent.getCumQty(), "0"));
    report.setString(
        AvgPx.FIELD, FixWireValues.fallbackDecimal(executionEvent.getAveragePx(), "0"));
    report.setString(ClOrdID.FIELD, FixWireValues.clientOrderIdForExecution(executionEvent));
    report.setString(
        Symbol.FIELD,
        executionEvent.getSymbol().isBlank() ? state.symbol() : executionEvent.getSymbol());
    report.setString(TransactTime.FIELD, FixWireValues.transactTime(executionEvent, clock));

    if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCELED
        && !executionEvent.getOrigClOrdId().isBlank()) {
      report.setString(OrigClOrdID.FIELD, executionEvent.getOrigClOrdId());
    }
    if (isFill(executionEvent.getExecutionType())) {
      report.setString(
          LastQty.FIELD, FixWireValues.fallbackDecimal(executionEvent.getFillQty(), "0"));
      report.setString(
          LastPx.FIELD, FixWireValues.fallbackDecimal(executionEvent.getFillPx(), "0"));
    }
    if (!executionEvent.getText().isBlank()) {
      report.setString(Text.FIELD, executionEvent.getText());
    }
    return report;
  }

  private ExecutionReport baseReport(
      FixOrderSnapshot order, FixExecutionIdentity execution, char execType, char orderStatus) {
    final ExecutionReport report = new ExecutionReport();
    report.setString(OrderID.FIELD, order.orderId().value());
    report.setString(ExecID.FIELD, execution.executionId().value());
    report.setChar(ExecType.FIELD, execType);
    report.setChar(OrdStatus.FIELD, orderStatus);
    report.setChar(quickfix.field.Side.FIELD, FixWireValues.mapSide(order.side()));
    report.setString(ClOrdID.FIELD, order.clientOrderId().value());
    report.setString(Symbol.FIELD, order.symbol().value());
    report.setString(TransactTime.FIELD, FixWireValues.format(execution.transactTime()));
    return report;
  }

  private boolean isFill(ExecutionType executionType) {
    return executionType == ExecutionType.EXECUTION_TYPE_PARTIAL_FILL
        || executionType == ExecutionType.EXECUTION_TYPE_FILL;
  }
}
