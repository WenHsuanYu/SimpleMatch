package com.simplematch.quickfixgateway.fix;

import quickfix.FieldNotFound;

/** Describes the wire-safe rejection to return for invalid inbound FIX values. */
record FixInboundValidationFailure(String reasonCode, String reasonText) {
  static FixInboundValidationFailure fromException(String reasonCode, Exception failure) {
    final String reasonText =
        failure instanceof FieldNotFound
            ? "required FIX field is missing"
            : failure.getMessage() == null ? "normalized command is invalid" : failure.getMessage();
    return new FixInboundValidationFailure(reasonCode, reasonText);
  }
}
