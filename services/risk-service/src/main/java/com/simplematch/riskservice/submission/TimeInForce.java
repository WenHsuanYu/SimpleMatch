package com.simplematch.riskservice.submission;

/** Domain-side time-in-force used by submission commands. */
public enum TimeInForce {
  TIME_IN_FORCE_UNSPECIFIED,
  TIME_IN_FORCE_ROD,
  TIME_IN_FORCE_IOC,
  TIME_IN_FORCE_FOK
}
