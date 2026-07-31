package com.simplematch.quickfixgateway.fix;

/** Describes the wire-safe rejection to return for an invalid FIX identity field. */
record FixIdentityValidationFailure(String reasonCode, String reasonText) {}
