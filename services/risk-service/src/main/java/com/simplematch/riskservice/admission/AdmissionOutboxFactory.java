package com.simplematch.riskservice.admission;

import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.orders.v2.OrderAdmissionRejected;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.time.Clock;
import java.util.Objects;

/** Creates versioned v2 accepted or rejected admission events for the risk outbox. */
public final class AdmissionOutboxFactory {
  private final String topic;
  private final Clock clock;

  /** Creates an outbox factory with the configured orders-validated topic. */
  public AdmissionOutboxFactory(String topic, Clock clock) {
    this.topic = Objects.requireNonNull(topic, "topic");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Builds the terminal event matching a journal outcome. */
  public OutboxRecord create(AdmissionJournalEntry entry) {
    final AdmissionCommand command = entry.command();
    final AdmissionIdentity identity = command.identity();
    final AdmissionOrder order = command.order();
    final String eventId = identity.commandId().value().toString();
    final long now = clock.millis();
    final EventMetadata metadata =
        EventMetadata.newBuilder()
            .setSchemaVersion("v2")
            .setEventId(eventId)
            .setCreatedAtUnixMs(now)
            .setSourceService("risk-service")
            .setCorrelationId(eventId)
            .build();
    final VenueInstrument instrument =
        VenueInstrument.newBuilder()
            .setSymbol(order.instrument().symbol().value())
            .setVenueMic(order.instrument().venueMic().value())
            .build();
    final byte[] payload;
    final String payloadType;
    if (entry.lifecycle().state() == AdmissionState.ACCEPTED) {
      payload =
          OrderAdmissionAccepted.newBuilder()
              .setMetadata(metadata)
              .setCommandId(identity.commandId().value().toString())
              .setOrderId(identity.orderId().value().toString())
              .setAccountId(identity.accountId().value().toString())
              .setInstrument(instrument)
              .setRoutingSnapshotId(
                  command.routing().snapshotId().value() == null
                      ? ""
                      : command.routing().snapshotId().value().toString())
              .setRoutingPartition(
                  entry.route().routingPartition() == null
                      ? 0
                      : entry.route().routingPartition())
              .build()
              .toByteArray();
      payloadType = OrderAdmissionAccepted.getDescriptor().getFullName();
    } else {
      final AdmissionFailure failure = rejection(entry);
      payload =
          OrderAdmissionRejected.newBuilder()
              .setMetadata(metadata)
              .setCommandId(identity.commandId().value().toString())
              .setOrderId(identity.orderId().value().toString())
              .setAccountId(identity.accountId().value().toString())
              .setInstrument(instrument)
              .setReason(mapReason(failure.reasonCode().value()))
              .setReasonDetail(failure.detail().value())
              .build()
              .toByteArray();
      payloadType = OrderAdmissionRejected.getDescriptor().getFullName();
    }
    return OutboxRecord.create(
        new OutboxRecord.EventInfo(eventId, now),
        OutboxRecord.Routing.withoutPartition(
            topic, identity.orderId().value().toString()),
        new OutboxRecord.PayloadEnvelope(payload, payloadType, "{\"schema_version\":\"v2\"}"),
        new OutboxRecord.AggregateRef("order_admission", identity.orderId().value().toString()));
  }

  private static AdmissionFailure rejection(AdmissionJournalEntry entry) {
    if (entry.lifecycle().decision() instanceof AdmissionDecision.Rejected rejected) {
      return rejected.failure();
    }
    throw new IllegalArgumentException("only rejected admissions have a rejection event");
  }

  private com.simplematch.contracts.orders.v2.AdmissionRejectReason mapReason(String reason) {
    if ("INSUFFICIENT_AVAILABLE_NOTIONAL".equals(reason)
        || "INSUFFICIENT_AVAILABLE_POSITION".equals(reason)) {
      return com.simplematch.contracts.orders.v2.AdmissionRejectReason
          .ADMISSION_REJECT_REASON_RISK_LIMIT;
    }
    if ("DUPLICATE_COMMAND".equals(reason)) {
      return com.simplematch.contracts.orders.v2.AdmissionRejectReason
          .ADMISSION_REJECT_REASON_DUPLICATE_COMMAND;
    }
    if ("UNAVAILABLE".equals(reason)) {
      return com.simplematch.contracts.orders.v2.AdmissionRejectReason
          .ADMISSION_REJECT_REASON_UNAVAILABLE;
    }
    return com.simplematch.contracts.orders.v2.AdmissionRejectReason
        .ADMISSION_REJECT_REASON_INVALID_COMMAND;
  }
}
