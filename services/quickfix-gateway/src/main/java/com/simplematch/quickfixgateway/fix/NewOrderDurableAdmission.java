package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalDurableCommandWriter;
import quickfix.SessionID;

/** Persists validated new orders before submitting them synchronously to Risk. */
final class NewOrderDurableAdmission {
  private final WalDurableCommandWriter durableCommandWriter;
  private final RiskSubmissionResponder riskSubmissionResponder;

  NewOrderDurableAdmission(
      WalDurableCommandWriter durableCommandWriter,
      RiskSubmissionResponder riskSubmissionResponder) {
    this.durableCommandWriter = durableCommandWriter;
    this.riskSubmissionResponder = riskSubmissionResponder;
  }

  RiskSubmissionResult admit(PreparedNewOrder preparedOrder, SessionID sessionId) {
    durableCommandWriter.appendForSubmission(preparedOrder.walRecord());
    final OrderCommand command = preparedOrder.walRecord().toOrderCommand();
    return riskSubmissionResponder.submitNewOrder(
        command, sessionId, preparedOrder.walRecord(), preparedOrder.preparedAt());
  }
}
