package com.simplematch.quickfixgateway.risk;

import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Derives the stable opaque Risk order identity from durable FIX order identity. */
public final class RiskOrderIdentityDeriver {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  /** Returns the stable Risk order UUID from one durable Gateway command. */
  public String derive(WalRecord record) {
    Objects.requireNonNull(record, "record");
    if (record.createdAtUnixMs() <= 0) {
      throw new IllegalArgumentException("created_at_unix_ms must be positive");
    }
    final LocalDate tradingDay =
        Instant.ofEpochMilli(record.createdAtUnixMs()).atZone(TAIPEI).toLocalDate();
    return derive(record, tradingDay);
  }

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
