package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import com.simplematch.quickfixgateway.wal.WalAppender;
import quickfix.SessionID;

/** Appends a prepared new order before submitting its command to Risk Admission. */
final class NewOrderDurableAdmission {
  private final WalAppender walAppender;
  private final RiskSubmissionResponder riskSubmissionResponder;

  NewOrderDurableAdmission(
      WalAppender walAppender, RiskSubmissionResponder riskSubmissionResponder) {
    this.walAppender = walAppender;
    this.riskSubmissionResponder = riskSubmissionResponder;
  }

  /**
   * Persists the order first, then returns the risk-service decision for that record.
   *
   * @param preparedOrder validated normalized order to append and submit
   * @param sessionId originating FIX session for risk rejection rendering
   * @return the risk-service admission decision
   */
  RiskSubmissionResult admit(PreparedNewOrder preparedOrder, SessionID sessionId) {
    walAppender.appendAndFlush(preparedOrder.walRecord());
    return riskSubmissionResponder.submitNewOrder(
        preparedOrder.command(),
        sessionId,
        preparedOrder.walRecord(),
        preparedOrder.preparedAt());
  }
}
