package com.simplematch.riskservice.submission;

import java.util.UUID;

/**
 * Selects the first stable rejection for a normalized submission in its published precedence
 * order.
 */
final class SubmissionRejectionPolicy {
  private static final int MAX_PERSISTED_IDENTIFIER_LENGTH = 255;
  private static final int MAX_PERSISTED_FIX_IDENTITY_LENGTH = 64;

  /**
   * Returns the first rejection required by the submission contract, or {@code null} when accepted.
   */
  SubmissionRejection firstRejection(ResolvedSubmissionCommand command) {
    if (command.isCompletelyUnspecified()) {
      return rejection("EMPTY_COMMAND", "risk command payload is required");
    }

    final SubmissionCommand payload = command.payload();
    final SubmissionRejection identityRejection = identityRejection(payload);
    if (identityRejection != null) {
      return identityRejection;
    }
    final SubmissionRejection storageRejection = storageRejection(payload);
    if (storageRejection != null) {
      return storageRejection;
    }
    final SubmissionRejection commandRejection = commandRejection(command);
    if (commandRejection != null) {
      return commandRejection;
    }
    return null;
  }

  private SubmissionRejection identityRejection(SubmissionCommand payload) {
    final SubmissionCommand.RequestIdentity identity = payload.requestMetadata().identity();
    final SubmissionCommand.FixIdentity fixIdentity = payload.requestMetadata().fixIdentity();
    if (fixIdentity.clOrdId().isBlank()) {
      return rejection("MISSING_CL_ORD_ID", "cl_ord_id is required");
    }
    if (identity.orderId().isBlank()) {
      return rejection("MISSING_ORDER_ID", "order_id is required");
    }
    if (fixIdentity.senderCompId().isBlank()) {
      return rejection("MISSING_SENDER_COMP_ID", "sender_comp_id is required");
    }
    if (fixIdentity.targetCompId().isBlank()) {
      return rejection("MISSING_TARGET_COMP_ID", "target_comp_id is required");
    }
    return null;
  }

  private SubmissionRejection storageRejection(SubmissionCommand payload) {
    final SubmissionCommand.RequestIdentity identity = payload.requestMetadata().identity();
    final SubmissionCommand.FixIdentity fixIdentity = payload.requestMetadata().fixIdentity();
    final SubmissionCommand.OrderDetails order = payload.orderDetails();
    if (exceedsPersistedIdentifierLength(identity.commandId().value())) {
      return oversized("OVERSIZED_REQUEST_ID", "request_id", MAX_PERSISTED_IDENTIFIER_LENGTH);
    }
    if (!isUuid(identity.commandId().value())) {
      return rejection("INVALID_REQUEST_ID", "request_id must be a UUID");
    }
    if (exceedsPersistedIdentifierLength(identity.orderId().value())) {
      return oversized("OVERSIZED_ORDER_ID", "order_id", MAX_PERSISTED_IDENTIFIER_LENGTH);
    }
    if (exceedsPersistedFixIdentityLength(fixIdentity.senderCompId().value())) {
      return oversized(
          "OVERSIZED_SENDER_COMP_ID", "sender_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH);
    }
    if (exceedsPersistedFixIdentityLength(fixIdentity.targetCompId().value())) {
      return oversized(
          "OVERSIZED_TARGET_COMP_ID", "target_comp_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH);
    }
    if (exceedsPersistedFixIdentityLength(fixIdentity.clOrdId().value())) {
      return oversized("OVERSIZED_CL_ORD_ID", "cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH);
    }
    if (exceedsPersistedFixIdentityLength(fixIdentity.origClOrdId().value())) {
      return oversized(
          "OVERSIZED_ORIG_CL_ORD_ID", "orig_cl_ord_id", MAX_PERSISTED_FIX_IDENTITY_LENGTH);
    }
    if (exceedsPersistedIdentifierLength(order.symbol())) {
      return oversized("OVERSIZED_SYMBOL", "symbol", MAX_PERSISTED_IDENTIFIER_LENGTH);
    }
    return null;
  }

  private SubmissionRejection commandRejection(ResolvedSubmissionCommand command) {
    if (command.commandType() == CommandType.COMMAND_TYPE_NEW) {
      return newOrderRejection(command.payload());
    }
    if (command.commandType() == CommandType.COMMAND_TYPE_CANCEL) {
      final SubmissionCommand.FixIdentity fixIdentity =
          command.payload().requestMetadata().fixIdentity();
      if (fixIdentity.origClOrdId().isBlank()) {
        return rejection(
            "MISSING_ORIG_CL_ORD_ID", "orig_cl_ord_id is required for cancel requests");
      }
    }
    return null;
  }

  private SubmissionRejection newOrderRejection(SubmissionCommand payload) {
    final SubmissionCommand.RequestIdentity identity = payload.requestMetadata().identity();
    final SubmissionCommand.OrderDetails order = payload.orderDetails();
    if (identity.accountId().isBlank()) {
      return rejection("MISSING_ACCOUNT_ID", "account_id is required");
    }
    if (order.symbol().isBlank()) {
      return rejection("MISSING_SYMBOL", "symbol is required");
    }
    if (order.quantity().isBlank()) {
      return rejection("MISSING_QUANTITY", "quantity is required");
    }
    if (order.side() == Side.SIDE_UNSPECIFIED) {
      return rejection("MISSING_SIDE", "side is required");
    }
    if (order.orderType() == OrderType.ORDER_TYPE_LIMIT && order.price().isBlank()) {
      return rejection("MISSING_PRICE", "price is required for limit orders");
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

  private static SubmissionRejection oversized(String code, String fieldName, int maxLength) {
    return rejection(code, fieldName + " must be <= " + maxLength + " characters");
  }

  private static SubmissionRejection rejection(String code, String detail) {
    return new SubmissionRejection(
        new SubmissionRejection.Code(code), new SubmissionRejection.Detail(detail));
  }
}
