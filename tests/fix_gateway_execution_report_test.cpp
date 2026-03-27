#include <gtest/gtest.h>

#include "ExecutionReportBuilder.h"

#include <quickfix/Fields.h>

namespace {

TEST(ExecutionReportBuilder, BuildsPendingNewExecutionReport) {
  const FIX::OrderID orderId{"O-C1"};
  const FIX::ExecID execId{"E1"};
  const FIX::ClOrdID clOrdId{"C1"};
  const FIX::Symbol symbol{"AAPL"};
  const FIX::Side side{FIX::Side_BUY};
  const FIX::OrderQty orderQty{10};

  const auto er = simplematch::fixgw::BuildPendingNewExecutionReport(
      orderId, execId, clOrdId, symbol, side, orderQty);

  FIX::OrderID gotOrderId;
  er.getField(gotOrderId);
  EXPECT_EQ(gotOrderId.getValue(), orderId.getValue());

  FIX::ExecID gotExecId;
  er.getField(gotExecId);
  EXPECT_EQ(gotExecId.getValue(), execId.getValue());

  FIX::ClOrdID gotClOrdId;
  er.getField(gotClOrdId);
  EXPECT_EQ(gotClOrdId.getValue(), clOrdId.getValue());

  FIX::Symbol gotSymbol;
  er.getField(gotSymbol);
  EXPECT_EQ(gotSymbol.getValue(), symbol.getValue());

  FIX::Side gotSide;
  er.getField(gotSide);
  EXPECT_EQ(gotSide.getValue(), side.getValue());

  FIX::ExecType execType;
  er.getField(execType);
  EXPECT_EQ(execType.getValue(), FIX::ExecType_PENDING_NEW);

  FIX::OrdStatus ordStatus;
  er.getField(ordStatus);
  EXPECT_EQ(ordStatus.getValue(), FIX::OrdStatus_PENDING_NEW);

  FIX::LeavesQty leavesQty;
  er.getField(leavesQty);
  EXPECT_EQ(leavesQty.getValue(), orderQty.getValue());

  FIX::CumQty cumQty;
  er.getField(cumQty);
  EXPECT_EQ(cumQty.getValue(), 0);

  FIX::AvgPx avgPx;
  er.getField(avgPx);
  EXPECT_EQ(avgPx.getValue(), 0);

  EXPECT_TRUE(er.isSetField(FIX::FIELD::TransactTime));
}

} // namespace
