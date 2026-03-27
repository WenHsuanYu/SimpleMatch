#include "ExecutionReportBuilder.h"

#include <quickfix/FieldTypes.h>

namespace simplematch::fixgw {

FIX44::ExecutionReport BuildPendingNewExecutionReport(
    const FIX::OrderID& orderId,
    const FIX::ExecID& execId,
    const FIX::ClOrdID& clOrdId,
    const FIX::Symbol& symbol,
    const FIX::Side& side,
    const FIX::OrderQty& orderQty) {
  FIX44::ExecutionReport er(
      orderId,
      execId,
      FIX::ExecType(FIX::ExecType_PENDING_NEW),
      FIX::OrdStatus(FIX::OrdStatus_PENDING_NEW),
      side,
      FIX::LeavesQty(orderQty.getValue()),
      FIX::CumQty(0),
      FIX::AvgPx(0));

  er.setField(clOrdId);
  er.setField(symbol);
  er.setField(FIX::TransactTime(FIX::UtcTimeStamp::now()));

  return er;
}

} // namespace simplematch::fixgw
