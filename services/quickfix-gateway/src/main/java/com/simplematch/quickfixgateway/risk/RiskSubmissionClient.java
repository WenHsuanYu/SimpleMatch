package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v1.OrderCommand;

public interface RiskSubmissionClient {
  RiskSubmissionResult submitNewOrder(OrderCommand command);

  RiskSubmissionResult submitCancel(OrderCommand command);
}