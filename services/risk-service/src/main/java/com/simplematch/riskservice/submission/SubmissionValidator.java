package com.simplematch.riskservice.submission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Validates normalized risk submissions before they enter the transactional persistence flow. */
@SuppressWarnings(
    "PMD.TooManyMethods") // Submission policy keeps acceptance and durable-result construction
                          // local.
public final class SubmissionValidator {
  private static final int MAX_PERSISTED_IDENTIFIER_LENGTH = 255;
  private static final int MAX_PERSISTED_FIX_IDENTITY_LENGTH = 64;
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  private final Clock clock;

  /**
   * Creates a validator anchored to the provided clock.
   *
   * @param clock the clock used to derive persistence timestamps and fallback trading days
   */
  public SubmissionValidator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Evaluates a normalized submission command and returns the accepted or rejected decision.
   *
   * @param command the normalized submission command, or {@code null} for an unspecified command
   * @return the submission decision that should be persisted
   */
  @SuppressWarnings(
      "PMD.CyclomaticComplexity") // Validation order defines stable rejection precedence.
  public SubmissionDecision evaluate(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand =
        command == null ? ResolvedSubmissionCommand.unspecified() : command;
    final SubmissionCommand payload = normalizedCommand.payload();
    final CommandType resolvedCommandType = normalizedCommand.commandType();
    final long now = clock.instant().toEpochMilli();
    final LocalDate tradingDay = resolveTradingDay(payload);

    if (normalizedCommand.isCompletelyUnspecified()) {
      return rejected(
          ResolvedSubmissionCommand.unspecified(),
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("EMPTY_COMMAND"),
              new SubmissionRejection.Detail("risk command payload is required")));
    }

    if (payload.clOrdIdValue().isBlank()) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("MISSING_CL_ORD_ID"),
              new SubmissionRejection.Detail("cl_ord_id is required")));
    }

    if (payload.orderIdValue().isBlank()) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("MISSING_ORDER_ID"),
              new SubmissionRejection.Detail("order_id is required")));
    }

    if (payload.senderCompIdValue().isBlank()) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("MISSING_SENDER_COMP_ID"),
              new SubmissionRejection.Detail("sender_comp_id is required")));
    }

    if (payload.targetCompIdValue().isBlank()) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("MISSING_TARGET_COMP_ID"),
              new SubmissionRejection.Detail("target_comp_id is required")));
    }

    final SubmissionDecision oversizedIdentifierDecision =
        oversizedIdentifierDecision(payload, now, normalizedCommand);
    if (oversizedIdentifierDecision != null) {
      return oversizedIdentifierDecision;
    }

    if (resolvedCommandType == CommandType.COMMAND_TYPE_NEW) {
      if (payload.accountIdValue().isBlank()) {
        return rejected(
            normalizedCommand,
            now,
            new SubmissionRejection(
                new SubmissionRejection.Code("MISSING_ACCOUNT_ID"),
                new SubmissionRejection.Detail("account_id is required")));
      }
      if (payload.symbol().isBlank()) {
        return rejected(
            normalizedCommand,
            now,
            new SubmissionRejection(
                new SubmissionRejection.Code("MISSING_SYMBOL"),
                new SubmissionRejection.Detail("symbol is required")));
      }
      if (payload.quantityValue().isBlank()) {
        return rejected(
            normalizedCommand,
            now,
            new SubmissionRejection(
                new SubmissionRejection.Code("MISSING_QUANTITY"),
                new SubmissionRejection.Detail("quantity is required")));
      }
      if (payload.side() == Side.SIDE_UNSPECIFIED) {
        return rejected(
            normalizedCommand,
            now,
            new SubmissionRejection(
                new SubmissionRejection.Code("MISSING_SIDE"),
                new SubmissionRejection.Detail("side is required")));
      }
      if (payload.orderType() == OrderType.ORDER_TYPE_LIMIT && payload.priceValue().isBlank()) {
        return rejected(
            normalizedCommand,
            now,
            new SubmissionRejection(
                new SubmissionRejection.Code("MISSING_PRICE"),
                new SubmissionRejection.Detail("price is required for limit orders")));
      }
    }

    if (resolvedCommandType == CommandType.COMMAND_TYPE_CANCEL
        && payload.origClOrdIdValue().isBlank()) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("MISSING_ORIG_CL_ORD_ID"),
              new SubmissionRejection.Detail("orig_cl_ord_id is required for cancel requests")));
    }

    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                payload.commandIdValue(), payload.orderIdValue(), resolvedCommandType),
            new FixSubmissionIdentity(
                payload.senderCompIdValue(),
                payload.targetCompIdValue(),
                tradingDay,
                payload.clOrdIdValue(),
                payload.origClOrdIdValue()),
            new PersistedFixIdentity(payload.clOrdIdValue(), payload.origClOrdIdValue(), false),
            SubmissionOutcome.acceptedOutcome(),
            now),
        normalizedCommand);
  }

  private SubmissionDecision rejected(
      ResolvedSubmissionCommand normalizedCommand,
      long createdAtUnixMs,
      SubmissionRejection rejection) {
    final SubmissionCommand payload = normalizedCommand.payload();
    return new SubmissionDecision(
        new SubmissionResult(
            new SubmissionReference(
                new SubmissionCommand.CommandId(persistedIdentifier(payload.commandId())),
                new SubmissionCommand.OrderId(persistedIdentifier(payload.orderId())),
                normalizedCommand.commandType()),
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
        normalizedCommand);
  }

  private SubmissionDecision oversizedIdentifierDecision(
      SubmissionCommand payload,
      long now,
      ResolvedSubmissionCommand normalizedCommand) {
    if (exceedsPersistedIdentifierLength(payload.commandId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_REQUEST_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage("request_id", MAX_PERSISTED_IDENTIFIER_LENGTH))));
    }
    if (!isUuid(payload.commandId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("INVALID_REQUEST_ID"),
              new SubmissionRejection.Detail("request_id must be a UUID")));
    }
    if (exceedsPersistedIdentifierLength(payload.orderId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_ORDER_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage("order_id", MAX_PERSISTED_IDENTIFIER_LENGTH))));
    }
    if (exceedsPersistedFixIdentityLength(payload.senderCompId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_SENDER_COMP_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage(
                      "sender_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH))));
    }
    if (exceedsPersistedFixIdentityLength(payload.targetCompId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_TARGET_COMP_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage(
                      "target_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH))));
    }
    if (exceedsPersistedFixIdentityLength(payload.clOrdId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_CL_ORD_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage("cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH))));
    }
    if (exceedsPersistedFixIdentityLength(payload.origClOrdId())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_ORIG_CL_ORD_ID"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage(
                      "orig_cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH))));
    }
    if (exceedsPersistedIdentifierLength(payload.symbol())) {
      return rejected(
          normalizedCommand,
          now,
          new SubmissionRejection(
              new SubmissionRejection.Code("OVERSIZED_SYMBOL"),
              new SubmissionRejection.Detail(
                  oversizedIdentifierMessage("symbol", MAX_PERSISTED_IDENTIFIER_LENGTH))));
    }
    return null;
  }

  private static boolean exceedsPersistedIdentifierLength(String value) {
    return value != null && value.length() > MAX_PERSISTED_IDENTIFIER_LENGTH;
  }

  private static boolean exceedsPersistedFixIdentityLength(String value) {
    return value != null && value.length() > MAX_PERSISTED_FIX_IDENTITY_LENGTH;
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException illegalArgumentException) {
      return false;
    }
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

  private static String oversizedIdentifierMessage(String fieldName, int maxLength) {
    return fieldName + " must be <= " + maxLength + " characters";
  }

  private static boolean businessKeySurrogated(SubmissionCommand payload) {
    return payload != null
        && (exceedsPersistedFixIdentityLength(payload.senderCompId())
            || exceedsPersistedFixIdentityLength(payload.targetCompId())
            || exceedsPersistedFixIdentityLength(payload.clOrdId()));
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
    final LocalDate payloadTradingDay = payload == null ? null : payload.tradingDay();
    if (payloadTradingDay != null) {
      return payloadTradingDay;
    }
    return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
  }
}
