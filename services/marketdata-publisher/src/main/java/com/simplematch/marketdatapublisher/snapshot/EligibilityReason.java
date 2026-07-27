package com.simplematch.marketdatapublisher.snapshot;

/** Explains why an imported instrument is outside the phase-one trading scope. */
public enum EligibilityReason {
  /** The instrument is a regular-board listed common stock on a supported Taiwan venue. */
  ELIGIBLE,
  /** The source identifies a venue other than XTAI or ROCO. */
  UNSUPPORTED_VENUE,
  /** The source identifies a security type other than a regular-board common stock. */
  UNSUPPORTED_SECURITY_TYPE
}
