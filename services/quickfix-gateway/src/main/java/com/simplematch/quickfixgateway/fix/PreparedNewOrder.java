package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Instant;
import java.util.Objects;

/** Represents one validated new order ready for durable admission. */
record PreparedNewOrder(WalRecord walRecord, Instant preparedAt) {
  PreparedNewOrder {
    Objects.requireNonNull(walRecord, "walRecord");
    Objects.requireNonNull(preparedAt, "preparedAt");
  }

  /** Returns the compatibility command represented by the prepared durable record. */
  OrderCommand command() {
    return walRecord.toOrderCommand();
  }
}
