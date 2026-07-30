package com.simplematch.accountservice.reservation;

import com.simplematch.contracts.common.v1.Side;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Trading facts that determine how much account authority a reserve operation requires.
 *
 * <p>Instrument, quantity, and price are explicit value objects. This keeps the ubiquitous
 * language visible and prevents two decimal values from being exchanged positionally.
 *
 * @param symbol the instrument symbol
 * @param side the requested order side
 * @param quantity the positive quantity to reserve
 * @param limitPrice the optional positive limit price
 */
public record ReservationTerms(
        InstrumentSymbol symbol,
        Side side,
        ReservationQuantity quantity,
        LimitPrice limitPrice) {
    /** Requires valid reservation terms. */
    public ReservationTerms {
        symbol = Objects.requireNonNull(symbol, "symbol");
        side = Objects.requireNonNull(side, "side");
        quantity = Objects.requireNonNull(quantity, "quantity");
        limitPrice = Objects.requireNonNull(limitPrice, "limitPrice");
        if (side == Side.SIDE_UNSPECIFIED) {
            throw new IllegalArgumentException("side must be specified");
        }
    }

    /** Instrument symbol involved in the reservation. */
    public record InstrumentSymbol(String value) {
        /** Requires a nonblank instrument symbol. */
        public InstrumentSymbol {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("symbol must not be blank");
            }
        }
    }

    /** Positive order quantity whose authority is reserved. */
    public record ReservationQuantity(BigDecimal value) {
        /** Requires a positive reservation quantity. */
        public ReservationQuantity {
            value = requirePositive(value, "quantity");
        }
    }

    /** Optional positive limit price used to calculate reserved notional. */
    public record LimitPrice(BigDecimal value) {
        /** Requires a positive value when a price is present. */
        public LimitPrice {
            if (value != null) {
                value = requirePositive(value, "limit_price");
            }
        }

        /** Returns an explicitly absent limit price. */
        public static LimitPrice absent() {
            return new LimitPrice(null);
        }

        /** Returns whether a limit price is present. */
        public boolean isPresent() {
            return value != null;
        }
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
