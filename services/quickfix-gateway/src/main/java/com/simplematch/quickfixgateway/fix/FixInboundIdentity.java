package com.simplematch.quickfixgateway.fix;

/** Holds validated acceptor-side FIX identity values and an optional rejection reason. */
record FixInboundIdentity(
    String senderCompId, String targetCompId, FixIdentityValidationFailure failure) {
  boolean valid() {
    return failure == null;
  }
}
