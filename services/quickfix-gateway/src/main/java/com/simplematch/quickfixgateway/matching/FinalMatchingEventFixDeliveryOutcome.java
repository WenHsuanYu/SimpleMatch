package com.simplematch.quickfixgateway.matching;

/** Result of durably accepting one final Matching Event for eventual FIX delivery. */
public enum FinalMatchingEventFixDeliveryOutcome {
  /** The raw event inbox and all required delivery intents were persisted. */
  APPLIED,
  /** The exact same final event had already been persisted and required no new delivery intent. */
  DUPLICATE
}
