package com.simplematch.riskservice.submission;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

public final class SubmissionValidator {
    private final Clock clock;

    public SubmissionValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

        public SubmissionDecision evaluate(ResolvedSubmissionCommand command, String idempotencyKey) {
                final ResolvedSubmissionCommand normalizedCommand = command == null
                                ? ResolvedSubmissionCommand.unspecified()
                                : command;
                final SubmissionCommand payload = normalizedCommand.payload();
                final CommandType resolvedCommandType = normalizedCommand.commandType();
        final long now = clock.instant().toEpochMilli();
                final LocalDate tradingDay = resolveTradingDay(payload);

        if (normalizedCommand.isCompletelyUnspecified()) {
            return rejected(
                    idempotencyKey,
                    "",
                    "",
                    "",
                    "",
                    CommandType.COMMAND_TYPE_UNSPECIFIED,
                    now,
                    "EMPTY_COMMAND",
                    "risk command payload is required",
                                        ResolvedSubmissionCommand.unspecified());
        }

                if (payload.clientOrderIdValue().isBlank()) {
            return rejected(
                    idempotencyKey,
                                        payload.commandId(),
                                        payload.orderId(),
                    "",
                                        payload.originalClientOrderId(),
                                        resolvedCommandType,
                    now,
                    "MISSING_CLIENT_ORDER_ID",
                    "client_order_id is required",
                    normalizedCommand);
        }

                if (payload.orderIdValue().isBlank()) {
            return rejected(
                    idempotencyKey,
                                        payload.commandId(),
                    "",
                                        payload.clientOrderId(),
                                        payload.originalClientOrderId(),
                                        resolvedCommandType,
                    now,
                    "MISSING_ORDER_ID",
                    "order_id is required",
                    normalizedCommand);
        }

                if (resolvedCommandType == CommandType.COMMAND_TYPE_NEW) {
                        if (payload.accountIdValue().isBlank()) {
                return rejected(
                        idempotencyKey,
                                                payload.commandId(),
                                                payload.orderId(),
                                                payload.clientOrderId(),
                                                payload.originalClientOrderId(),
                                                resolvedCommandType,
                        now,
                        "MISSING_ACCOUNT_ID",
                        "account_id is required",
                        normalizedCommand);
            }
                        if (payload.symbol().isBlank()) {
                return rejected(
                        idempotencyKey,
                                                payload.commandId(),
                                                payload.orderId(),
                                                payload.clientOrderId(),
                                                payload.originalClientOrderId(),
                                                resolvedCommandType,
                        now,
                        "MISSING_SYMBOL",
                        "symbol is required",
                        normalizedCommand);
            }
                        if (payload.quantityValue().isBlank()) {
                return rejected(
                        idempotencyKey,
                                                payload.commandId(),
                                                payload.orderId(),
                                                payload.clientOrderId(),
                                                payload.originalClientOrderId(),
                                                resolvedCommandType,
                        now,
                        "MISSING_QUANTITY",
                        "quantity is required",
                        normalizedCommand);
            }
                        if (payload.side() == Side.SIDE_UNSPECIFIED) {
                return rejected(
                        idempotencyKey,
                                                payload.commandId(),
                                                payload.orderId(),
                                                payload.clientOrderId(),
                                                payload.originalClientOrderId(),
                                                resolvedCommandType,
                        now,
                        "MISSING_SIDE",
                        "side is required",
                        normalizedCommand);
            }
                        if (payload.orderType() == OrderType.ORDER_TYPE_LIMIT
                                        && payload.priceValue().isBlank()) {
                return rejected(
                        idempotencyKey,
                                                payload.commandId(),
                                                payload.orderId(),
                                                payload.clientOrderId(),
                                                payload.originalClientOrderId(),
                                                resolvedCommandType,
                        now,
                        "MISSING_PRICE",
                        "price is required for limit orders",
                        normalizedCommand);
            }
        }

                if (resolvedCommandType == CommandType.COMMAND_TYPE_CANCEL
                                && payload.originalClientOrderIdValue().isBlank()) {
            return rejected(
                    idempotencyKey,
                                        payload.commandId(),
                                        payload.orderId(),
                                        payload.clientOrderId(),
                    "",
                                        resolvedCommandType,
                    now,
                    "MISSING_ORIGINAL_CLIENT_ORDER_ID",
                    "original_client_order_id is required for cancel requests",
                    normalizedCommand);
        }

        return new SubmissionDecision(
                new SubmissionResult(
                        idempotencyKey,
                        payload.commandId(),
                        payload.sessionId(),
                        tradingDay,
                        payload.orderId(),
                        payload.clientOrderId(),
                        payload.originalClientOrderId(),
                        resolvedCommandType,
                        true,
                        "",
                        "",
                        now),
                normalizedCommand);
    }

    private SubmissionDecision rejected(
            String idempotencyKey,
            String requestId,
            String orderId,
            String clientOrderId,
            String originalClientOrderId,
            CommandType commandType,
            long createdAtUnixMs,
            String reasonCode,
            String reasonText,
            ResolvedSubmissionCommand normalizedCommand) {
        return new SubmissionDecision(
                new SubmissionResult(
                        idempotencyKey,
                        requestId,
                        normalizedCommand.payload().sessionId(),
                        resolveTradingDay(normalizedCommand.payload()),
                        orderId,
                        clientOrderId,
                        originalClientOrderId,
                        commandType,
                        false,
                        reasonCode,
                        reasonText,
                        createdAtUnixMs),
                normalizedCommand);
    }

        private LocalDate resolveTradingDay(SubmissionCommand payload) {
                final LocalDate payloadTradingDay = payload == null ? null : payload.tradingDay();
                if (payloadTradingDay != null) {
                        return payloadTradingDay;
                }
                return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        }
}