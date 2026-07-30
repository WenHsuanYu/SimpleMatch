package com.simplematch.contracts.v2;

/** Signals that a value cannot cross a v2 domain-contract seam. */
public final class DomainValidationException extends IllegalArgumentException {
  /** Creates an exception with the rejected contract detail. */
  public DomainValidationException(String message) {
    super(message);
  }
}
