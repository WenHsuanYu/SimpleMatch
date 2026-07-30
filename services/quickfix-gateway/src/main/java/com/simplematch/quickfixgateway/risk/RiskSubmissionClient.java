package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v1.OrderCommand;

/** Admits gateway commands through risk-service. */
public interface RiskSubmissionClient {
  /** Submits a new-order command to risk admission. */
  RiskSubmissionResult submitNewOrder(OrderCommand command);

  /** Submits a cancel command to risk admission. */
  RiskSubmissionResult submitCancel(OrderCommand command);
}
