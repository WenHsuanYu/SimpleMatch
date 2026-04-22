package com.simplematch.riskservice.store;

import com.simplematch.contracts.orders.v1.OrderCommand;

public interface SubmissionStore {
  StoredSubmission persist(OrderCommand command);
}