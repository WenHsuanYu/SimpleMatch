package com.simplematch.riskservice.submission;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Validates normalized risk submissions before they enter the transactional persistence flow.
 */
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
  public SubmissionDecision evaluate(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand = command == null
        ? ResolvedSubmissionCommand.unspecified()
        : command;
    final SubmissionCommand payload = normalizedCommand.payload();
    final CommandType resolvedCommandType = normalizedCommand.commandType();
    final long now = clock.instant().toEpochMilli();
    final LocalDate tradingDay = resolveTradingDay(payload);

    if (normalizedCommand.isCompletelyUnspecified()) {
      return rejected(
          "",
          "",
          "",
          "",
          CommandType.COMMAND_TYPE_UNSPECIFIED,
          now,
          "EMPTY_COMMAND",
          "risk command payload is required",
          ResolvedSubmissionCommand.unspecified());
    }

    if (payload.clOrdIdValue().isBlank()) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          "",
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "MISSING_CL_ORD_ID",
          "cl_ord_id is required",
          normalizedCommand);
    }

    if (payload.orderIdValue().isBlank()) {
      return rejected(
          payload.commandId(),
          "",
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "MISSING_ORDER_ID",
          "order_id is required",
          normalizedCommand);
    }

    if (payload.senderCompIdValue().isBlank()) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "MISSING_SENDER_COMP_ID",
          "sender_comp_id is required",
          normalizedCommand);
    }

    if (payload.targetCompIdValue().isBlank()) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "MISSING_TARGET_COMP_ID",
          "target_comp_id is required",
          normalizedCommand);
    }

    final SubmissionDecision oversizedIdentifierDecision = oversizedIdentifierDecision(
        payload,
        resolvedCommandType,
        now,
        normalizedCommand);
    if (oversizedIdentifierDecision != null) {
      return oversizedIdentifierDecision;
    }

    if (resolvedCommandType == CommandType.COMMAND_TYPE_NEW) {
      if (payload.accountIdValue().isBlank()) {
        return rejected(
            payload.commandId(),
            payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
            resolvedCommandType,
            now,
            "MISSING_ACCOUNT_ID",
            "account_id is required",
            normalizedCommand);
      }
      if (payload.symbol().isBlank()) {
        return rejected(
            payload.commandId(),
            payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
            resolvedCommandType,
            now,
            "MISSING_SYMBOL",
            "symbol is required",
            normalizedCommand);
      }
      if (payload.quantityValue().isBlank()) {
        return rejected(
            payload.commandId(),
            payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
            resolvedCommandType,
            now,
            "MISSING_QUANTITY",
            "quantity is required",
            normalizedCommand);
      }
      if (payload.side() == Side.SIDE_UNSPECIFIED) {
        return rejected(
            payload.commandId(),
            payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
            resolvedCommandType,
            now,
            "MISSING_SIDE",
            "side is required",
            normalizedCommand);
      }
      if (payload.orderType() == OrderType.ORDER_TYPE_LIMIT && payload.priceValue().isBlank()) {
        return rejected(
            payload.commandId(),
            payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
            resolvedCommandType,
            now,
            "MISSING_PRICE",
            "price is required for limit orders",
            normalizedCommand);
      }
    }

    if (resolvedCommandType == CommandType.COMMAND_TYPE_CANCEL
        && payload.origClOrdIdValue().isBlank()) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          "",
          resolvedCommandType,
          now,
          "MISSING_ORIG_CL_ORD_ID",
          "orig_cl_ord_id is required for cancel requests",
          normalizedCommand);
    }

    return new SubmissionDecision(
        new SubmissionResult(
            payload.commandId(),
            payload.senderCompId(),
            payload.targetCompId(),
            tradingDay,
            payload.orderId(),
            payload.clOrdId(),
            payload.origClOrdId(),
            resolvedCommandType,
            true,
            "",
            "",
            now),
        normalizedCommand);
  }

  private SubmissionDecision rejected(
      String requestId,
      String orderId,
      String clOrdId,
      String origClOrdId,
      CommandType commandType,
      long createdAtUnixMs,
      String reasonCode,
      String reasonText,
      ResolvedSubmissionCommand normalizedCommand) {
    return new SubmissionDecision(
        new SubmissionResult(
            persistedIdentifier(requestId),
      persistedBusinessKeyIdentifier(normalizedCommand.payload().senderCompId()),
      persistedBusinessKeyIdentifier(normalizedCommand.payload().targetCompId()),
            resolveTradingDay(normalizedCommand.payload()),
            persistedIdentifier(orderId),
        clOrdId,
        origClOrdId,
            commandType,
            false,
            reasonCode,
            reasonText,
        createdAtUnixMs,
      persistedBusinessKeyIdentifier(clOrdId),
      persistedFixIdentity(origClOrdId),
      businessKeySurrogated(normalizedCommand.payload())),
        normalizedCommand);
  }

  private SubmissionDecision oversizedIdentifierDecision(
      SubmissionCommand payload,
      CommandType resolvedCommandType,
      long now,
      ResolvedSubmissionCommand normalizedCommand) {
    if (exceedsPersistedIdentifierLength(payload.commandId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_REQUEST_ID",
              oversizedIdentifierMessage("request_id", MAX_PERSISTED_IDENTIFIER_LENGTH),
          normalizedCommand);
    }
        if (!isUuid(payload.commandId())) {
          return rejected(
            payload.commandId(),
            payload.orderId(),
            payload.clOrdId(),
            payload.origClOrdId(),
            resolvedCommandType,
            now,
            "INVALID_REQUEST_ID",
            "request_id must be a UUID",
            normalizedCommand);
        }
    if (exceedsPersistedIdentifierLength(payload.orderId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_ORDER_ID",
              oversizedIdentifierMessage("order_id", MAX_PERSISTED_IDENTIFIER_LENGTH),
          normalizedCommand);
    }
    if (exceedsPersistedFixIdentityLength(payload.senderCompId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_SENDER_COMP_ID",
              oversizedIdentifierMessage("sender_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH),
          normalizedCommand);
    }
            if (exceedsPersistedFixIdentityLength(payload.targetCompId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_TARGET_COMP_ID",
              oversizedIdentifierMessage("target_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH),
          normalizedCommand);
    }
            if (exceedsPersistedFixIdentityLength(payload.clOrdId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_CL_ORD_ID",
              oversizedIdentifierMessage("cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH),
          normalizedCommand);
    }
            if (exceedsPersistedFixIdentityLength(payload.origClOrdId())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_ORIG_CL_ORD_ID",
              oversizedIdentifierMessage("orig_cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH),
          normalizedCommand);
    }
    if (exceedsPersistedIdentifierLength(payload.symbol())) {
      return rejected(
          payload.commandId(),
          payload.orderId(),
          payload.clOrdId(),
          payload.origClOrdId(),
          resolvedCommandType,
          now,
          "OVERSIZED_SYMBOL",
          oversizedIdentifierMessage("symbol", MAX_PERSISTED_IDENTIFIER_LENGTH),
          normalizedCommand);
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