package com.simplematch.persistence.matching;

/** Outcome of one final Matching Event inbox claim and local persistence transaction. */
public enum MatchingEventPersistenceOutcome {
  /** The new event and every derived persistence record committed together. */
  APPLIED,
  /** The exact same raw event was already committed and caused no second mutation. */
  DUPLICATE
}
