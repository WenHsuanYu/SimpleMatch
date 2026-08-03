package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.util.Objects;

/**
 * FIX-facing order facts required to build execution reports.
 *
 * <p>This is an anti-corruption-layer value object: it keeps QuickFIX mapping concerns outside the
 * order-admission domain. Each same-shaped FIX field has a distinct Java type, so order identity,
 * client identity, symbol, and quantity cannot be exchanged positionally.
 *
 * @param orderId the server-assigned order identifier
 * @param clientOrderId the FIX ClOrdID
 * @param symbol the FIX Symbol
 * @param side the order side
 * @param quantity the canonical decimal order quantity
 */
public record FixOrderSnapshot(
    OrderId orderId, ClientOrderId clientOrderId, Symbol symbol, Side side, Quantity quantity) {
  /** Requires a complete FIX report context. */
  public FixOrderSnapshot {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(clientOrderId, "clientOrderId");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(quantity, "quantity");
  }

  /** Creates the report context from the durable gateway WAL record. */
  public static FixOrderSnapshot from(WalRecord record) {
    Objects.requireNonNull(record, "record");
    return new FixOrderSnapshot(
        new OrderId(record.orderId()),
        new ClientOrderId(record.clOrdId()),
        new Symbol(record.symbol()),
        record.side(),
        new Quantity(record.quantity()));
  }

  /** Creates the partial order facts available when a cancel arrives before local state exists. */
  public static FixOrderSnapshot cancelFallback(WalRecord record) {
    Objects.requireNonNull(record, "record");
    return new FixOrderSnapshot(
        new OrderId(record.orderId()),
        new ClientOrderId(record.origClOrdId()),
        new Symbol(""),
        Side.SIDE_UNSPECIFIED,
        new Quantity(""));
  }

  /** Server-assigned order identity rendered in FIX tag 37. */
  public record OrderId(String value) {
    /** Requires a nonblank order identity. */
    public OrderId {
      value = requireNonBlank(value, "order_id");
    }
  }

  /** Client order identity rendered in FIX tag 11. */
  public record ClientOrderId(String value) {
    /** Requires a nonblank client order identity. */
    public ClientOrderId {
      value = requireNonBlank(value, "cl_ord_id");
    }
  }

  /** Instrument symbol rendered in FIX tag 55. */
  public record Symbol(String value) {
    /** Normalizes the absent symbol used by a cancellation fallback to an empty value. */
    public Symbol {
      value = Objects.requireNonNullElse(value, "");
    }
  }

  /** Canonical decimal quantity text rendered in quantity fields when present. */
  public record Quantity(String value) {
    /** Normalizes an absent quantity to the explicit empty representation. */
    public Quantity {
      value = Objects.requireNonNullElse(value, "");
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
