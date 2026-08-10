package com.simplematch.quickfixgateway.risk;

import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Derives the stable opaque Risk order identity from FIX business identity. */
final class RiskOrderIdentityDeriver {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  String derive(WalRecord record) {
    Objects.requireNonNull(record, "record");
    final String clientOrderId =
        record.command() instanceof WalCommand.Cancel ? record.origClOrdId() : record.clOrdId();
    final String tradingDay =
        Instant.ofEpochMilli(record.createdAtUnixMs())
            .atZone(TAIPEI)
            .toLocalDate()
            .toString();
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
