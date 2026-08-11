package com.simplematch.accountservice.matching;

/** Describes whether a final Matching Event changed Account Authority state or was replayed. */
public enum FinalMatchingEventAccountOutcome {
  APPLIED,
  DUPLICATE
}
