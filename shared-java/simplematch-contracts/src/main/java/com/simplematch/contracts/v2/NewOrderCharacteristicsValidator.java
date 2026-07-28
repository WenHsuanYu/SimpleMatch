package com.simplematch.contracts.v2;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.orders.v2.NewOrderCommand;

/**
 * Validates the order-specific values that only exist on a v2 new-order command.
 */
final class NewOrderCharacteristicsValidator {
    private NewOrderCharacteristicsValidator() {
    }

    static void validate(NewOrderCommand command) {
        validateSide(command);
        new ShareQuantity(command.getQuantity().getShares());
        validatePrice(command);
        validateTif(command);
        validateCurrency(command);
        TradingDay.parse(command.getTradingDay().getIsoDate());
        validateSessionState(command);
        validateRoutingSnapshot(command);
        validateEstimatedNotional(command);
    }

    private static void validateSide(NewOrderCommand command) {
        if (command.getSide() == Side.SIDE_UNSPECIFIED) {
            throw new DomainValidationException("side is required");
        }
    }

    private static void validatePrice(NewOrderCommand command) {
        if (command.getOrderType() == OrderType.ORDER_TYPE_LIMIT) {
            new TwdPrice(command.getLimitPrice().getUnits());
            return;
        }
        if (command.getOrderType() != OrderType.ORDER_TYPE_MARKET) {
            throw new DomainValidationException("order_type is required");
        }
        if (command.hasLimitPrice()) {
            throw new DomainValidationException("market orders must not carry limit_price");
        }
    }

    private static void validateTif(NewOrderCommand command) {
        if (command.getTif() == com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_UNSPECIFIED) {
            throw new DomainValidationException("tif is required");
        }
    }

    private static void validateCurrency(NewOrderCommand command) {
        if (command.getCurrency() != Currency.CURRENCY_TWD) {
            throw new DomainValidationException("currency must be TWD");
        }
    }

    private static void validateSessionState(NewOrderCommand command) {
        if (command.getSessionState() == SessionState.SESSION_STATE_UNSPECIFIED) {
            throw new DomainValidationException("session_state is required");
        }
    }

    private static void validateRoutingSnapshot(NewOrderCommand command) {
        if (!command.getRoutingSnapshotId().isBlank()) {
            V2Identifiers.SnapshotId.parse(command.getRoutingSnapshotId());
        }
    }

    private static void validateEstimatedNotional(NewOrderCommand command) {
        if (command.hasEstimatedNotional() && command.getEstimatedNotional().getUnits() <= 0) {
            throw new DomainValidationException("estimated_notional must be positive when present");
        }
    }
}
