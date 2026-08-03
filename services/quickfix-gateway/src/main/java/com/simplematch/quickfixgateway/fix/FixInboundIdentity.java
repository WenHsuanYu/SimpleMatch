package com.simplematch.quickfixgateway.fix;

/** Holds acceptor-side FIX identity values and an optional rejection reason. */
record FixInboundIdentity(
    String senderCompId, String targetCompId, FixInboundValidationFailure failure) {
  boolean valid() {
    return failure == null;
  }
}
