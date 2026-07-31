package com.simplematch.riskservice.submission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/** Builds persistence-ready accepted and rejected submission decisions. */
final class SubmissionDecisionFactory {
  private static final int MAX_PERSISTED_IDENTIFIER_LENGTH = 255;
  private static final int MAX_PERSISTED_FIX_IDENTITY_LENGTH = 64;
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  private final Clock clock;

  SubmissionDecisionFactory(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  SubmissionDecision accepted(ResolvedSubmissionCommand command, long createdAtUnixMs) {
    final SubmissionCommand payload = command.payload();
    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                payload.commandIdValue(), payload.orderIdValue(), command.commandType()),
            new FixSubmissionIdentity(
                payload.senderCompIdValue(),
                payload.targetCompIdValue(),
                resolveTradingDay(payload),
                payload.clOrdIdValue(),
                payload.origClOrdIdValue()),
            new PersistedFixIdentity(payload.clOrdIdValue(), payload.origClOrdIdValue(), false),
            SubmissionOutcome.acceptedOutcome(),
            createdAtUnixMs),
        command);
  }

  /**
   * Builds a rejection that bounds durable identifiers while retaining raw client order identifiers
   * for the gRPC response and the normalized command for outbox construction.
   */
  SubmissionDecision rejected(
      ResolvedSubmissionCommand command, long createdAtUnixMs, SubmissionRejection rejection) {
    final SubmissionCommand payload = command.payload();
    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                new SubmissionCommand.CommandId(persistedIdentifier(payload.commandId())),
                new SubmissionCommand.OrderId(persistedIdentifier(payload.orderId())),
                command.commandType()),
            new FixSubmissionIdentity(
                new SubmissionCommand.SenderCompId(
                    persistedBusinessKeyIdentifier(payload.senderCompId())),
                new SubmissionCommand.TargetCompId(
                    persistedBusinessKeyIdentifier(payload.targetCompId())),
                resolveTradingDay(payload),
                payload.clOrdIdValue(),
                payload.origClOrdIdValue()),
            new PersistedFixIdentity(
                new SubmissionCommand.ClOrdId(persistedBusinessKeyIdentifier(payload.clOrdId())),
                new SubmissionCommand.OrigClOrdId(persistedFixIdentity(payload.origClOrdId())),
                businessKeySurrogated(payload)),
            SubmissionOutcome.rejectedOutcome(rejection),
            createdAtUnixMs),
        command);
  }

  private static String persistedIdentifier(String value) {
    final String resolved = value == null ? "" : value;
    if (resolved.length() <= MAX_PERSISTED_IDENTIFIER_LENGTH) {
      return resolved;
    }
    return resolved.substring(0, MAX_PERSISTED_IDENTIFIER_LENGTH);
  }

  private static String persistedBusinessKeyIdentifier(String value) {
    return persistedFixIdentity(value);
  }

  private static String persistedFixIdentity(String value) {
    final String resolved = value == null ? "" : value;
    if (resolved.length() <= MAX_PERSISTED_FIX_IDENTITY_LENGTH) {
      return resolved;
    }
    return sha256Hex(resolved);
  }

  private static boolean businessKeySurrogated(SubmissionCommand payload) {
    return exceedsPersistedFixIdentityLength(payload.senderCompId())
        || exceedsPersistedFixIdentityLength(payload.targetCompId())
        || exceedsPersistedFixIdentityLength(payload.clOrdId());
  }

  private static boolean exceedsPersistedFixIdentityLength(String value) {
    return value != null && value.length() > MAX_PERSISTED_FIX_IDENTITY_LENGTH;
  }

  private static String sha256Hex(String value) {
    try {
      return HEX_FORMAT.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
      throw new IllegalStateException("SHA-256 is not available", noSuchAlgorithmException);
    }
  }

  private LocalDate resolveTradingDay(SubmissionCommand payload) {
    final LocalDate payloadTradingDay = payload.tradingDay();
    if (payloadTradingDay != null) {
      return payloadTradingDay;
    }
    return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
