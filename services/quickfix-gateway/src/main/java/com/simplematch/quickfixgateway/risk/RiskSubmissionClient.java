package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;

/** Admits typed v2 Gateway commands through risk-service. */
public interface RiskSubmissionClient {
  /** Submits a typed new-order command to Risk admission. */
  RiskSubmissionResult submitNewOrder(NewOrderCommand command);

  /** Submits a typed cancellation command to Risk admission. */
  RiskSubmissionResult submitCancel(CancelOrderCommand command);
}
