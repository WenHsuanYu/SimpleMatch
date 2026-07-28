package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.orders.v2.OrderAdmissionRejected;
import com.simplematch.riskservice.outbox.OutboxRecord;

import java.time.Clock;
import java.util.Objects;

/**
 * Creates versioned v2 accepted or rejected admission events for the risk outbox.
 */
public final class AdmissionOutboxFactory {
    private final String topic;
    private final Clock clock;

    /**
     * Creates an outbox factory with the configured orders-validated topic.
     */
    public AdmissionOutboxFactory(String topic, Clock clock) {
        this.topic = Objects.requireNonNull(topic, "topic");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Builds the terminal event matching a journal outcome.
     */
    public OutboxRecord create(AdmissionJournalEntry entry) {
        final String eventId = entry.commandId().toString();
        final long now = clock.millis();
        final EventMetadata metadata = EventMetadata.newBuilder().setSchemaVersion("v2").setEventId(eventId)
                .setCreatedAtUnixMs(now).setSourceService("risk-service")
                .setCorrelationId(entry.commandId().toString()).build();
        final VenueInstrument instrument = VenueInstrument.newBuilder().setSymbol(entry.symbol())
                .setVenueMic(entry.venueMic()).build();
        final byte[] payload;
        final String payloadType;
        if (entry.state() == AdmissionState.ACCEPTED) {
            payload = OrderAdmissionAccepted.newBuilder().setMetadata(metadata)
                    .setCommandId(entry.commandId().toString()).setOrderId(entry.orderId().toString())
                    .setAccountId(entry.accountId().toString()).setInstrument(instrument)
                    .setRoutingSnapshotId(entry.routingSnapshotId() == null ? "" : entry.routingSnapshotId().toString())
                    .setRoutingPartition(entry.routingPartition() == null ? 0 : entry.routingPartition()).build().toByteArray();
            payloadType = OrderAdmissionAccepted.getDescriptor().getFullName();
        } else {
            payload = OrderAdmissionRejected.newBuilder().setMetadata(metadata)
                    .setCommandId(entry.commandId().toString()).setOrderId(entry.orderId().toString())
                    .setAccountId(entry.accountId().toString()).setInstrument(instrument)
                    .setReason(mapReason(entry.reasonCode())).setReasonDetail(entry.reasonDetail()).build().toByteArray();
            payloadType = OrderAdmissionRejected.getDescriptor().getFullName();
        }
        return OutboxRecord.create(new OutboxRecord.EventInfo(eventId, now),
                OutboxRecord.Routing.withoutPartition(topic, entry.orderId().toString()),
                new OutboxRecord.PayloadEnvelope(payload, payloadType, "{\"schema_version\":\"v2\"}"),
                new OutboxRecord.AggregateRef("order_admission", entry.orderId().toString()));
    }

    private com.simplematch.contracts.orders.v2.AdmissionRejectReason mapReason(String reason) {
        if ("INSUFFICIENT_AVAILABLE_NOTIONAL".equals(reason) || "INSUFFICIENT_AVAILABLE_POSITION".equals(reason)) {
            return com.simplematch.contracts.orders.v2.AdmissionRejectReason.ADMISSION_REJECT_REASON_RISK_LIMIT;
        }
        if ("DUPLICATE_COMMAND".equals(reason)) {
            return com.simplematch.contracts.orders.v2.AdmissionRejectReason.ADMISSION_REJECT_REASON_DUPLICATE_COMMAND;
        }
        if ("UNAVAILABLE".equals(reason)) {
            return com.simplematch.contracts.orders.v2.AdmissionRejectReason.ADMISSION_REJECT_REASON_UNAVAILABLE;
        }
        return com.simplematch.contracts.orders.v2.AdmissionRejectReason.ADMISSION_REJECT_REASON_INVALID_COMMAND;
    }
}
