package com.simplematch.quickfixgateway.risk;

import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Derives the stable opaque Risk order identity from durable FIX order identity. */
public final class RiskOrderIdentityDeriver {
  /** Returns the stable Risk order UUID for one durable Gateway command and trading day. */
  public String derive(WalRecord record, LocalDate tradingDay) {
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(tradingDay, "tradingDay");
    final String clientOrderId =
        record.command() instanceof WalCommand.Cancel ? record.origClOrdId() : record.clOrdId();
    final String identityKey =
        record.senderCompId()
            + "\u0000"
            + record.targetCompId()
            + "\u0000"
            + tradingDay
            + "\u0000"
            + clientOrderId;
    return UUID.nameUUIDFromBytes(identityKey.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
