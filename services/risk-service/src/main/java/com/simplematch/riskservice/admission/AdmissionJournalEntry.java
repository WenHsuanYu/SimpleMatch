package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable durable admission journal row.
 */
public record AdmissionJournalEntry(
        UUID commandId,
        UUID orderId,
        UUID accountId,
        String symbol,
        String venueMic,
        String side,
        long quantity,
        Long limitPriceUnits,
        String orderType,
        String tif,
        LocalDate tradingDay,
        String senderCompId,
        String targetCompId,
        String clOrdId,
        UUID routingSnapshotId,
        Integer routingPartition,
        AdmissionState state,
        UUID reservationId,
        String reasonCode,
        String reasonDetail,
        long version,
        long createdAtUnixMs,
        long updatedAtUnixMs) {
    /**
     * Validates identity, state, and monotonic journal values.
     */
    public AdmissionJournalEntry {
        if (commandId == null || orderId == null || accountId == null || symbol == null || symbol.isBlank()
                || venueMic == null || venueMic.isBlank() || quantity <= 0 || tradingDay == null
                || senderCompId == null || targetCompId == null || clOrdId == null || clOrdId.isBlank()
                || state == null || version < 0 || updatedAtUnixMs < createdAtUnixMs) {
            throw new IllegalArgumentException("admission journal fields are invalid");
        }
        reasonCode = reasonCode == null ? "" : reasonCode;
        reasonDetail = reasonDetail == null ? "" : reasonDetail;
    }

    /**
     * Returns a pending journal entry from a validated command.
     */
    public static AdmissionJournalEntry pending(AdmissionCommand command, long now) {
        final AdmissionIdentity identity = command.identity();
        final AdmissionOrder order = command.order();
        final AdmissionOrder.Instrument instrument = order.instrument();
        final AdmissionOrder.Characteristics characteristics = order.characteristics();
        final AdmissionFixIdentity fixIdentity = command.fixIdentity();
        return new AdmissionJournalEntry(
                identity.commandId().value(),
                identity.orderId().value(),
                identity.accountId().value(),
                instrument.symbol().value(),
                instrument.venueMic().value(),
                characteristics.side().value(),
                characteristics.quantity().value(),
                characteristics.limitPrice().value(),
                characteristics.orderType().value(),
                characteristics.timeInForce().value(),
                order.tradingDay(),
                fixIdentity.senderCompId().value(),
                fixIdentity.targetCompId().value(),
                fixIdentity.clOrdId().value(),
                command.routing().snapshotId().value(),
                null,
                AdmissionState.PENDING,
                null,
                "",
                "",
                0,
                now,
                now);
    }

    /**
     * Returns whether a retry carries the same persisted command content.
     */
    public boolean matches(AdmissionCommand command) {
        final AdmissionIdentity identity = command.identity();
        final AdmissionOrder order = command.order();
        final AdmissionOrder.Instrument instrument = order.instrument();
        final AdmissionOrder.Characteristics characteristics = order.characteristics();
        final AdmissionFixIdentity fixIdentity = command.fixIdentity();
        return commandId.equals(identity.commandId().value())
                && orderId.equals(identity.orderId().value())
                && accountId.equals(identity.accountId().value())
                && symbol.equals(instrument.symbol().value())
                && venueMic.equals(instrument.venueMic().value())
                && side.equals(characteristics.side().value())
                && quantity == characteristics.quantity().value()
                && Objects.equals(limitPriceUnits, characteristics.limitPrice().value())
                && orderType.equals(characteristics.orderType().value())
                && tif.equals(characteristics.timeInForce().value())
                && tradingDay.equals(order.tradingDay())
                && senderCompId.equals(fixIdentity.senderCompId().value())
                && targetCompId.equals(fixIdentity.targetCompId().value())
                && clOrdId.equals(fixIdentity.clOrdId().value())
                && Objects.equals(routingSnapshotId, command.routing().snapshotId().value());
    }
}
