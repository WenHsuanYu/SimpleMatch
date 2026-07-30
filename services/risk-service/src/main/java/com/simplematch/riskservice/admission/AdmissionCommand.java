package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.util.UUID;

/** Transport-independent validated v2 order command carrier. */
public record AdmissionCommand(
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
    UUID routingSnapshotId) {}
