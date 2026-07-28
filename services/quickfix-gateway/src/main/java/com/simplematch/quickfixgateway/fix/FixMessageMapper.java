package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@SuppressWarnings("PMD.TooManyMethods") // FIX protocol mapper keeps one wire-format seam.
public final class FixMessageMapper {
    private static final DateTimeFormatter FIX_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public FixMessageMapper(Clock clock) {
        this.clock = clock;
    }

    public ExecutionReport buildPendingNew(
            String orderId,
            String execId,
            String clientOrderId,
            String symbol,
            Side side,
            String quantity,
            Instant transactTime) {
        final ExecutionReport report = new ExecutionReport();
        report.setString(OrderID.FIELD, orderId);
        report.setString(ExecID.FIELD, execId);
        report.setChar(ExecType.FIELD, 'A');
        report.setChar(OrdStatus.FIELD, 'A');
        report.setChar(quickfix.field.Side.FIELD, mapSide(side));
        report.setString(LeavesQty.FIELD, normalizeDecimal(quantity));
        report.setString(CumQty.FIELD, "0");
        report.setString(AvgPx.FIELD, "0");
        report.setString(ClOrdID.FIELD, clientOrderId);
        report.setString(Symbol.FIELD, symbol);
        report.setString(TransactTime.FIELD, FIX_TIMESTAMP.format(transactTime));
        return report;
    }

    public ExecutionReport buildRejected(
            String orderId,
            String execId,
            String clientOrderId,
            String symbol,
            Side side,
            String quantity,
            String text,
            Instant transactTime) {
        final ExecutionReport report = new ExecutionReport();
        report.setString(OrderID.FIELD, orderId);
        report.setString(ExecID.FIELD, execId);
        report.setChar(ExecType.FIELD, '8');
        report.setChar(OrdStatus.FIELD, '8');
        report.setChar(quickfix.field.Side.FIELD, mapSide(side));
        report.setString(LeavesQty.FIELD, "0");
        report.setString(CumQty.FIELD, "0");
        report.setString(AvgPx.FIELD, "0");
        report.setString(ClOrdID.FIELD, clientOrderId);
        report.setString(Symbol.FIELD, symbol);
        report.setString(TransactTime.FIELD, FIX_TIMESTAMP.format(transactTime));
        if (quantity != null && !quantity.isBlank()) {
            report.setString(OrderQty.FIELD, normalizeDecimal(quantity));
        }
        if (text != null && !text.isBlank()) {
            report.setString(Text.FIELD, text);
        }
        return report;
    }

    public Message buildExecutionReport(ExecutionEvent executionEvent, OrderSessionState state) {
        final ExecutionReport report = new ExecutionReport();
        report.setString(OrderID.FIELD, executionEvent.getOrderId());
        report.setString(ExecID.FIELD, executionEvent.getExecId());
        report.setChar(ExecType.FIELD, mapExecType(executionEvent.getExecutionType()));
        report.setChar(OrdStatus.FIELD, mapOrdStatus(executionEvent.getExecutionType()));
        report.setChar(quickfix.field.Side.FIELD, mapSide(executionEvent.getSide()));
        report.setString(LeavesQty.FIELD, fallbackDecimal(executionEvent.getLeavesQty(), state.quantity()));
        report.setString(CumQty.FIELD, fallbackDecimal(executionEvent.getCumQty(), "0"));
        report.setString(AvgPx.FIELD, fallbackDecimal(executionEvent.getAveragePx(), "0"));
        report.setString(ClOrdID.FIELD, clientOrderIdForExecution(executionEvent));
        report.setString(Symbol.FIELD, executionEvent.getSymbol().isBlank() ? state.symbol() : executionEvent.getSymbol());
        report.setString(TransactTime.FIELD, transactTime(executionEvent));

        if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCELED
                && !executionEvent.getOrigClOrdId().isBlank()) {
            report.setString(OrigClOrdID.FIELD, executionEvent.getOrigClOrdId());
        }

        if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_PARTIAL_FILL
                || executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_FILL) {
            report.setString(LastQty.FIELD, fallbackDecimal(executionEvent.getFillQty(), "0"));
            report.setString(LastPx.FIELD, fallbackDecimal(executionEvent.getFillPx(), "0"));
        }

        if (!executionEvent.getText().isBlank()) {
            report.setString(Text.FIELD, executionEvent.getText());
        }

        return report;
    }

    public OrderCancelReject buildOrderCancelReject(ExecutionEvent executionEvent, OrderSessionState state) {
        final String cancelClientOrderId = executionEvent.getCancelClOrdId();
        final String originalClientOrderId = executionEvent.getOrigClOrdId();

        if (cancelClientOrderId.isBlank() || originalClientOrderId.isBlank()) {
            throw new IllegalStateException("missing cancel request context for order " + state.orderId());
        }

        final OrderCancelReject reject = new OrderCancelReject();
        reject.setString(OrderID.FIELD, executionEvent.getOrderId());
        reject.setString(ClOrdID.FIELD, cancelClientOrderId);
        reject.setString(OrigClOrdID.FIELD, originalClientOrderId);
        reject.setChar(OrdStatus.FIELD, state.currentOrdStatus());
        reject.setChar(CxlRejResponseTo.FIELD, '1');
        reject.setInt(CxlRejReason.FIELD, mapCancelRejectReason(executionEvent.getText()));
        if (!executionEvent.getText().isBlank()) {
            reject.setString(Text.FIELD, executionEvent.getText());
        }
        return reject;
    }

    public OrderCancelReject buildOrderCancelReject(
            String orderId,
            String cancelClientOrderId,
            String originalClientOrderId,
            char ordStatus,
            String text) {
        final OrderCancelReject reject = new OrderCancelReject();
        reject.setString(OrderID.FIELD, orderId);
        reject.setString(ClOrdID.FIELD, cancelClientOrderId);
        reject.setString(OrigClOrdID.FIELD, originalClientOrderId);
        reject.setChar(OrdStatus.FIELD, ordStatus);
        reject.setChar(CxlRejResponseTo.FIELD, '1');
        reject.setInt(CxlRejReason.FIELD, mapCancelRejectReason(text));
        if (text != null && !text.isBlank()) {
            reject.setString(Text.FIELD, text);
        }
        return reject;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    private char mapSide(Side side) {
        return switch (side) {
            case SIDE_SELL -> '2';
            case SIDE_BUY, SIDE_UNSPECIFIED -> '1';
            default -> '1';
        };
    }

    private char mapExecType(ExecutionType executionType) {
        return switch (executionType) {
            case EXECUTION_TYPE_PENDING_NEW -> 'A';
            case EXECUTION_TYPE_NEW -> '0';
            case EXECUTION_TYPE_PARTIAL_FILL -> '1';
            case EXECUTION_TYPE_FILL -> '2';
            case EXECUTION_TYPE_CANCELED -> '4';
            case EXECUTION_TYPE_REJECTED -> '8';
            case EXECUTION_TYPE_CANCEL_REJECTED, EXECUTION_TYPE_UNSPECIFIED -> '8';
            default -> '8';
        };
    }

    private char mapOrdStatus(ExecutionType executionType) {
        return switch (executionType) {
            case EXECUTION_TYPE_PENDING_NEW -> 'A';
            case EXECUTION_TYPE_NEW -> '0';
            case EXECUTION_TYPE_PARTIAL_FILL -> '1';
            case EXECUTION_TYPE_FILL -> '2';
            case EXECUTION_TYPE_CANCELED -> '4';
            case EXECUTION_TYPE_REJECTED -> '8';
            case EXECUTION_TYPE_CANCEL_REJECTED, EXECUTION_TYPE_UNSPECIFIED -> '8';
            default -> '8';
        };
    }

    private int mapCancelRejectReason(String text) {
        if (text == null || text.isBlank()) {
            return 99;
        }
        final String normalized = text.toUpperCase(Locale.ROOT);
        if (normalized.contains("UNKNOWN_ORDER")) {
            return 1;
        }
        if (normalized.contains("TOO_LATE") || normalized.contains("TOO LATE")) {
            return 0;
        }
        if (normalized.contains("DUPLICATE")) {
            return 6;
        }
        return 99;
    }

    private String clientOrderIdForExecution(ExecutionEvent executionEvent) {
        if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCELED
                && !executionEvent.getCancelClOrdId().isBlank()) {
            return executionEvent.getCancelClOrdId();
        }
        return executionEvent.getClOrdId();
    }

    private String transactTime(ExecutionEvent executionEvent) {
        if (executionEvent.hasMetadata() && executionEvent.getMetadata().getCreatedAtUnixMs() > 0) {
            return FIX_TIMESTAMP.format(Instant.ofEpochMilli(executionEvent.getMetadata().getCreatedAtUnixMs()));
        }
        return FIX_TIMESTAMP.format(now());
    }

    private String fallbackDecimal(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return normalizeDecimal(value);
        }
        return normalizeDecimal(fallback);
    }

    private String normalizeDecimal(String value) {
        if (value == null || value.isBlank()) {
            return "0";
        }
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
}
