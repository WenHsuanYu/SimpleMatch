package com.simplematch.accountservice.reservation;

import java.util.Objects;

/** One Account Authority effect translated from an external matching execution contract. */
public sealed interface MatchingAccountEffect
    permits MatchingAccountEffect.Fill, MatchingAccountEffect.Terminal {
  /** Stable execution identity used for Account idempotency. */
  String executionId();

  /** Stable order identity whose reservation is affected. */
  String orderId();

  /** Account identity expected to own the reservation. */
  String accountId();

  /** Instrument symbol expected to match the reservation. */
  String symbol();

  /** One matched quantity and price applied to an active reservation. */
  record Fill(
      String executionId,
      String orderId,
      String accountId,
      String symbol,
      ExecutionFill.FillQuantity quantity,
      ExecutionFill.FillPrice price)
      implements MatchingAccountEffect {
    /** Requires complete execution and reservation identity. */
    public Fill {
      executionId = requireText(executionId, "executionId");
      orderId = requireText(orderId, "orderId");
      accountId = requireText(accountId, "accountId");
      symbol = requireText(symbol, "symbol");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(price, "price");
    }
  }

  /** One terminal matching outcome that releases unused reservation authority. */
  record Terminal(
      String executionId,
      String orderId,
      String accountId,
      String symbol,
      ReleaseReservationOperation.ReleaseReason reason)
      implements MatchingAccountEffect {
    /** Requires complete execution and reservation identity. */
    public Terminal {
      executionId = requireText(executionId, "executionId");
      orderId = requireText(orderId, "orderId");
      accountId = requireText(accountId, "accountId");
      symbol = requireText(symbol, "symbol");
      Objects.requireNonNull(reason, "reason");
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
