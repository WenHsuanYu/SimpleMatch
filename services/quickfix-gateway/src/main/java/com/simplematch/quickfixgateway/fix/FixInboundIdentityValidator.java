package com.simplematch.quickfixgateway.fix;

import quickfix.SessionID;

/** Validates inbound FIX identity fields before they become durable gateway values. */
final class FixInboundIdentityValidator {
  private static final int MAX_FIX_IDENTITY_LENGTH = 64;

  private FixInboundIdentityValidator() {}

  static FixInboundIdentity validate(SessionID sessionId, String clOrdId, String origClOrdId) {
    final String senderCompId = sessionId.getTargetCompID();
    final String targetCompId = sessionId.getSenderCompID();
    return new FixInboundIdentity(
        senderCompId,
        targetCompId,
        firstFailure(senderCompId, targetCompId, clOrdId, origClOrdId));
  }

  private static FixIdentityValidationFailure firstFailure(
      String senderCompId, String targetCompId, String clOrdId, String origClOrdId) {
    final FixIdentityValidationFailure senderFailure =
        oversized("sender_comp_id", senderCompId, "OVERSIZED_SENDER_COMP_ID");
    if (senderFailure != null) {
      return senderFailure;
    }
    final FixIdentityValidationFailure targetFailure =
        oversized("target_comp_id", targetCompId, "OVERSIZED_TARGET_COMP_ID");
    if (targetFailure != null) {
      return targetFailure;
    }
    final FixIdentityValidationFailure clOrdIdFailure =
        oversized("cl_ord_id", clOrdId, "OVERSIZED_CL_ORD_ID");
    return clOrdIdFailure != null
        ? clOrdIdFailure
        : oversized("orig_cl_ord_id", origClOrdId, "OVERSIZED_ORIG_CL_ORD_ID");
  }

  private static FixIdentityValidationFailure oversized(
      String fieldName, String value, String reasonCode) {
    if (value == null || value.length() <= MAX_FIX_IDENTITY_LENGTH) {
      return null;
    }
    return new FixIdentityValidationFailure(
        reasonCode, fieldName + " must be <= " + MAX_FIX_IDENTITY_LENGTH + " characters");
  }
}
