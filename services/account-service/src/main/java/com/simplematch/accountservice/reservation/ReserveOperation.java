package com.simplematch.accountservice.reservation;

import com.simplematch.contracts.common.v1.Side;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Validated synchronous reserve operation received by account-service.
 *
 * @param requestId  the synchronous request identifier mapped from the upstream operation identity
 * @param orderId    the order that owns the reservation
 * @param accountId  the account that owns the reservation
 * @param symbol     the instrument symbol the reservation applies to
 * @param side       the side associated with the reservation request
 * @param quantity   the requested quantity to reserve
 * @param limitPrice the optional limit price carried with the request
 */
public record ReserveOperation(
        String requestId,
        String orderId,
        String accountId,
        String symbol,
        Side side,
        BigDecimal quantity,
        BigDecimal limitPrice) {
    public ReserveOperation {
        requestId = requireNonBlank(requestId, "request_id");
        orderId = requireNonBlank(orderId, "order_id");
        accountId = requireNonBlank(accountId, "account_id");
        symbol = requireNonBlank(symbol, "symbol");
        side = requireNonNullValue(side, "side");
        if (side == Side.SIDE_UNSPECIFIED) {
            throw new IllegalArgumentException("side must be specified");
        }
        quantity = requirePositive(quantity, "quantity");
        if (limitPrice != null && limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("limit_price must be positive when provided");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static <T> T requireNonNullValue(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }
}