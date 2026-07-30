package com.simplematch.accountservice.reservation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One immutable execution fill applied to a reservation.
 *
 * <p>The execution identifier is the idempotency identity. The optional aggregate sequence is
 * used to reject stale lifecycle delivery when the producing stream supplies one. Quantity and
 * price use different Java types so they cannot be exchanged at a call site.
 *
 * @param executionId the unique execution event identifier
 * @param aggregateSequence the optional monotonic order-stream sequence
 * @param quantity the positive filled quantity
 * @param price the positive execution price
 */
public record ExecutionFill(
        ExecutionId executionId,
        AggregateSequence aggregateSequence,
        FillQuantity quantity,
        FillPrice price) {
    /** Requires a complete, validated execution fill. */
    public ExecutionFill {
        executionId = Objects.requireNonNull(executionId, "executionId");
        aggregateSequence = Objects.requireNonNull(aggregateSequence, "aggregateSequence");
        quantity = Objects.requireNonNull(quantity, "quantity");
        price = Objects.requireNonNull(price, "price");
    }

    /** Returns the execution notional represented by this fill. */
    public BigDecimal notional() {
        return quantity.value().multiply(price.value());
    }

    /** Idempotency identity of one execution event. */
    public record ExecutionId(String value) {
        /** Requires a nonblank execution identifier. */
        public ExecutionId {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("execution_id must not be blank");
            }
        }
    }

    /** Optional monotonic sequence supplied by an ordered aggregate stream. */
    public record AggregateSequence(Long value) {
        /** Rejects a negative sequence while retaining an explicit absent value. */
        public AggregateSequence {
            if (value != null && value < 0) {
                throw new IllegalArgumentException(
                        "aggregate_sequence must be non-negative when provided");
            }
        }

        /** Returns an absent aggregate sequence. */
        public static AggregateSequence absent() {
            return new AggregateSequence(null);
        }
    }

    /** Positive quantity filled by one execution. */
    public record FillQuantity(BigDecimal value) {
        /** Requires a positive fill quantity. */
        public FillQuantity {
            value = requirePositive(value, "fill_quantity");
        }
    }

    /** Positive execution price. */
    public record FillPrice(BigDecimal value) {
        /** Requires a positive execution price. */
        public FillPrice {
            value = requirePositive(value, "fill_price");
        }
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
