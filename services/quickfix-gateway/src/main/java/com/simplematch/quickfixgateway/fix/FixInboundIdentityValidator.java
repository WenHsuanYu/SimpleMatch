package com.simplematch.quickfixgateway.fix;

import quickfix.SessionID;

/** Validates inbound FIX identity fields before they become durable gateway values. */
final class FixInboundIdentityValidator {
  private static final int MAX_FIX_IDENTITY_LENGTH = 64;

  private FixInboundIdentityValidator() {}

  static FixInboundIdentity validateNewOrder(SessionID sessionId, String clOrdId) {
    return validate(sessionId, clOrdId, null);
  }

  static FixInboundIdentity validateCancel(
      SessionID sessionId, String cancelClOrdId, String origClOrdId) {
    return validate(sessionId, cancelClOrdId, origClOrdId);
  }

  private static FixInboundIdentity validate(
      SessionID sessionId, String clOrdId, String origClOrdId) {
    final String senderCompId = sessionId.getTargetCompID();
    final String targetCompId = sessionId.getSenderCompID();
    return new FixInboundIdentity(
        senderCompId,
        targetCompId,
        firstFailure(senderCompId, targetCompId, clOrdId, origClOrdId));
  }

  private static FixInboundValidationFailure firstFailure(
      String senderCompId, String targetCompId, String clOrdId, String origClOrdId) {
    final FixInboundValidationFailure senderFailure =
        firstIdentityFailure(
            senderCompId, "sender_comp_id", "MISSING_SENDER_COMP_ID", "OVERSIZED_SENDER_COMP_ID");
    if (senderFailure != null) {
      return senderFailure;
    }
    final FixInboundValidationFailure targetFailure =
        firstIdentityFailure(
            targetCompId, "target_comp_id", "MISSING_TARGET_COMP_ID", "OVERSIZED_TARGET_COMP_ID");
    if (targetFailure != null) {
      return targetFailure;
    }
    final FixInboundValidationFailure clOrdIdFailure =
        firstIdentityFailure(clOrdId, "cl_ord_id", "MISSING_CL_ORD_ID", "OVERSIZED_CL_ORD_ID");
    if (clOrdIdFailure != null) {
      return clOrdIdFailure;
    }
    if (origClOrdId == null) {
      return null;
    }
    if (origClOrdId.isBlank()) {
      return new FixInboundValidationFailure(
          "MISSING_ORIG_CL_ORD_ID", "orig_cl_ord_id must not be blank");
    }
    return oversized("orig_cl_ord_id", origClOrdId, "OVERSIZED_ORIG_CL_ORD_ID");
  }

  private static FixInboundValidationFailure firstIdentityFailure(
      String value, String fieldName, String missingReasonCode, String oversizedReasonCode) {
    if (value == null || value.isBlank()) {
      return new FixInboundValidationFailure(
          missingReasonCode, fieldName + " must not be blank");
    }
    return oversized(fieldName, value, oversizedReasonCode);
  }

  private static FixInboundValidationFailure oversized(
      String fieldName, String value, String reasonCode) {
    if (value == null || value.length() <= MAX_FIX_IDENTITY_LENGTH) {
      return null;
    }
    return new FixInboundValidationFailure(
        reasonCode, fieldName + " must be <= " + MAX_FIX_IDENTITY_LENGTH + " characters");
  }
}
