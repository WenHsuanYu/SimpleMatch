package com.simplematch.accountservice.reservation;

import com.simplematch.accountservice.authority.AccountId;
import java.util.Objects;

/** One Account Authority effect translated from an external matching execution contract. */
public sealed interface MatchingAccountEffect
    permits MatchingAccountEffect.Fill, MatchingAccountEffect.Terminal {
  /** Stable execution identity used for Account idempotency. */
  ExecutionFill.ExecutionId executionId();

  /** Stable lifecycle identity of the order whose reservation is affected. */
  ReservationIdentity.OrderId orderId();

  /** Canonical Account-domain identity expected to own the reservation. */
  AccountId accountId();

  /** Instrument symbol expected to match the reservation. */
  ReservationTerms.InstrumentSymbol symbol();

  /** Matching-owned order state expected after applying one fill. */
  enum ResultingState {
    PARTIALLY_FILLED,
    FILLED
  }

  /** One matched quantity and price applied to an active reservation. */
  record Fill(
      ExecutionFill.ExecutionId executionId,
      ReservationIdentity.OrderId orderId,
      AccountId accountId,
      ReservationTerms.InstrumentSymbol symbol,
      ExecutionFill.FillQuantity quantity,
      ExecutionFill.FillPrice price,
      ResultingState resultingState)
      implements MatchingAccountEffect {
    /** Requires complete execution, reservation, and Matching state facts. */
    public Fill {
      Objects.requireNonNull(executionId, "executionId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(quantity, "quantity");
      Objects.requireNonNull(price, "price");
      Objects.requireNonNull(resultingState, "resultingState");
    }
  }

  /** One terminal matching outcome that releases unused reservation authority. */
  record Terminal(
      ExecutionFill.ExecutionId executionId,
      ReservationIdentity.OrderId orderId,
      AccountId accountId,
      ReservationTerms.InstrumentSymbol symbol,
      ReleaseReservationOperation.ReleaseReason reason)
      implements MatchingAccountEffect {
    /** Requires complete execution and reservation identity. */
    public Terminal {
      Objects.requireNonNull(executionId, "executionId");
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
