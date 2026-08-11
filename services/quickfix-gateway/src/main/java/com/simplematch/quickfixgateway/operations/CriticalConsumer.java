package com.simplematch.quickfixgateway.operations;

/** Critical `matching.events` consumers whose durable progress protects trading admission. */
public enum CriticalConsumer {
  /** The durable trade and fill persistence consumer. */
  PERSISTENCE,
  /** The account reservation and position lifecycle consumer. */
  ACCOUNT,
  /** The FIX delivery-intent and client report consumer. */
  QUICKFIX
}
