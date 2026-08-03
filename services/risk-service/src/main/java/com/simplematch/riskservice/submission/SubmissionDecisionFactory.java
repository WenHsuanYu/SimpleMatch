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
    final SubmissionCommand.RequestIdentity identity = payload.requestMetadata().identity();
    final SubmissionCommand.FixIdentity fixIdentity = payload.requestMetadata().fixIdentity();
    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                identity.commandId(), identity.orderId(), command.commandType()),
            new FixSubmissionIdentity(
                fixIdentity.senderCompId(),
                fixIdentity.targetCompId(),
                resolveTradingDay(payload),
                fixIdentity.clOrdId(),
                fixIdentity.origClOrdId()),
            new PersistedFixIdentity(fixIdentity.clOrdId(), fixIdentity.origClOrdId(), false),
            SubmissionOutcome.acceptedOutcome(),
            createdAtUnixMs),
        command);
  }

  /**
   * Builds a rejection that bounds durable identifiers while retaining raw client order identifiers
   * for the gRPC response and the normalized command for outbox construction.
   *
   * @param command the normalized command whose raw response identifiers and persistence-safe
   *     values are retained in the decision
   * @param createdAtUnixMs the decision creation time persisted with the submission result
   * @param rejection the rejection outcome to expose to the client and persist
   * @return a decision with persistence-safe durable identifiers and raw client response
   *     identifiers
   */
  SubmissionDecision rejected(
      ResolvedSubmissionCommand command, long createdAtUnixMs, SubmissionRejection rejection) {
    final SubmissionCommand payload = command.payload();
    final SubmissionCommand.RequestIdentity identity = payload.requestMetadata().identity();
    final SubmissionCommand.FixIdentity fixIdentity = payload.requestMetadata().fixIdentity();
    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                new SubmissionCommand.CommandId(persistedIdentifier(identity.commandId().value())),
                new SubmissionCommand.OrderId(persistedIdentifier(identity.orderId().value())),
                command.commandType()),
            new FixSubmissionIdentity(
                new SubmissionCommand.SenderCompId(
                    persistedBusinessKeyIdentifier(fixIdentity.senderCompId().value())),
                new SubmissionCommand.TargetCompId(
                    persistedBusinessKeyIdentifier(fixIdentity.targetCompId().value())),
                resolveTradingDay(payload),
                fixIdentity.clOrdId(),
                fixIdentity.origClOrdId()),
            new PersistedFixIdentity(
                new SubmissionCommand.ClOrdId(
                    persistedBusinessKeyIdentifier(fixIdentity.clOrdId().value())),
                new SubmissionCommand.OrigClOrdId(
                    persistedFixIdentity(fixIdentity.origClOrdId().value())),
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
    final SubmissionCommand.FixIdentity fixIdentity = payload.requestMetadata().fixIdentity();
    return exceedsPersistedFixIdentityLength(fixIdentity.senderCompId().value())
        || exceedsPersistedFixIdentityLength(fixIdentity.targetCompId().value())
        || exceedsPersistedFixIdentityLength(fixIdentity.clOrdId().value());
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
    final LocalDate payloadTradingDay = payload.requestMetadata().tradingDay();
    if (payloadTradingDay != null) {
      return payloadTradingDay;
    }
    return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
