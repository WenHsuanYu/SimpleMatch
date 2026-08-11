package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.v2.TwdPrice;
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
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.fix44.ExecutionReport;

/** Renders a durable final-event delivery intent into one stable FIX 4.4 execution report. */
public final class FinalFixExecutionReportMapper {
  /** Builds one report without consulting mutable process-local matching state. */
  public ExecutionReport render(FinalFixDeliveryIntent intent) {
    final FixOrderSnapshot order = intent.recipient().order();
    final FinalFixDeliveryReport reportFacts = intent.report();
    final ExecutionReport report = new ExecutionReport();
    report.setString(OrderID.FIELD, order.orderId().value());
    report.setString(ExecID.FIELD, reportFacts.executionId());
    report.setChar(ExecType.FIELD, reportFacts.executionType());
    report.setChar(OrdStatus.FIELD, reportFacts.orderStatus());
    report.setChar(quickfix.field.Side.FIELD, FixWireValues.mapOrderSide(order.side()));
    report.setString(ClOrdID.FIELD, order.clientOrderId().value());
    report.setString(Symbol.FIELD, order.symbol().value());
    report.setString(OrderQty.FIELD, FixWireValues.normalizeDecimal(order.quantity().value()));
    report.setString(LeavesQty.FIELD, Long.toString(reportFacts.leavesQuantity()));
    report.setString(CumQty.FIELD, Long.toString(reportFacts.cumulativeQuantity()));
    report.setString(AvgPx.FIELD, price(reportFacts.averagePriceUnits()));
    if (reportFacts.lastQuantity() > 0) {
      report.setString(LastQty.FIELD, Long.toString(reportFacts.lastQuantity()));
      report.setString(LastPx.FIELD, price(reportFacts.lastPriceUnits()));
    }
    if (!reportFacts.text().isBlank()) {
      report.setString(Text.FIELD, reportFacts.text());
    }
    return report;
  }

  private String price(long units) {
    return units == 0 ? "0" : new TwdPrice(units).toDecimalString();
  }
}
