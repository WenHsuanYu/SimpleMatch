package com.simplematch.quickfixgateway.kafka;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import java.util.ArrayList;
import java.util.List;

final class ExecutionEventRequirements {
  private ExecutionEventRequirements() {
  }

  static void validate(ExecutionEvent executionEvent) {
    final List<String> missingFields = new ArrayList<>();

    requireNonBlank(missingFields, "exec_id", executionEvent.getExecId());
    requireNonBlank(missingFields, "order_id", executionEvent.getOrderId());
    requireNonBlank(missingFields, "symbol", executionEvent.getSymbol());
    requireEnum(missingFields, "side", executionEvent.getSide() != Side.SIDE_UNSPECIFIED);

    if (isCancelFlow(executionEvent.getExecutionType())) {
      requireNonBlank(missingFields, "cancel_cl_ord_id", executionEvent.getCancelClOrdId());
      requireNonBlank(missingFields, "orig_cl_ord_id", executionEvent.getOrigClOrdId());
    } else {
      requireNonBlank(missingFields, "cl_ord_id", executionEvent.getClOrdId());
    }

    if (!missingFields.isEmpty()) {
      throw new IllegalArgumentException(
          "matching.executions event missing required fields: " + String.join(", ", missingFields));
    }
  }

  private static boolean isCancelFlow(ExecutionType executionType) {
    return executionType == ExecutionType.EXECUTION_TYPE_CANCELED
        || executionType == ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED;
  }

  private static void requireNonBlank(List<String> missingFields, String fieldName, String value) {
    if (value == null || value.isBlank()) {
      missingFields.add(fieldName);
    }
  }

  private static void requireEnum(List<String> missingFields, String fieldName, boolean present) {
    if (!present) {
      missingFields.add(fieldName);
    }
  }
}