package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable durable admission journal row. */
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
  /** Validates identity, state, and monotonic journal values. */
  public AdmissionJournalEntry {
    if (commandId == null
        || orderId == null
        || accountId == null
        || symbol == null
        || symbol.isBlank()
        || venueMic == null
        || venueMic.isBlank()
        || quantity <= 0
        || tradingDay == null
        || senderCompId == null
        || targetCompId == null
        || clOrdId == null
        || clOrdId.isBlank()
        || state == null
        || version < 0
        || updatedAtUnixMs < createdAtUnixMs) {
      throw new IllegalArgumentException("admission journal fields are invalid");
    }
    reasonCode = reasonCode == null ? "" : reasonCode;
    reasonDetail = reasonDetail == null ? "" : reasonDetail;
  }

  /** Returns a pending journal entry from a validated command. */
  public static AdmissionJournalEntry pending(AdmissionCommand command, long now) {
    return new AdmissionJournalEntry(
        command.commandId(),
        command.orderId(),
        command.accountId(),
        command.symbol(),
        command.venueMic(),
        command.side(),
        command.quantity(),
        command.limitPriceUnits(),
        command.orderType(),
        command.tif(),
        command.tradingDay(),
        command.senderCompId(),
        command.targetCompId(),
        command.clOrdId(),
        command.routingSnapshotId(),
        null,
        AdmissionState.PENDING,
        null,
        "",
        "",
        0,
        now,
        now);
  }

  /** Returns whether a retry carries the same persisted command content. */
  public boolean matches(AdmissionCommand command) {
    return commandId.equals(command.commandId())
        && orderId.equals(command.orderId())
        && accountId.equals(command.accountId())
        && symbol.equals(command.symbol())
        && venueMic.equals(command.venueMic())
        && side.equals(command.side())
        && quantity == command.quantity()
        && Objects.equals(limitPriceUnits, command.limitPriceUnits())
        && orderType.equals(command.orderType())
        && tif.equals(command.tif())
        && tradingDay.equals(command.tradingDay())
        && senderCompId.equals(command.senderCompId())
        && targetCompId.equals(command.targetCompId())
        && clOrdId.equals(command.clOrdId())
        && Objects.equals(routingSnapshotId, command.routingSnapshotId());
  }
}
