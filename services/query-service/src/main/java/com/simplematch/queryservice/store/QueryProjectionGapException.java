package com.simplematch.queryservice.store;

/** Indicates that a query projection source offset cannot be applied contiguously. */
public final class QueryProjectionGapException extends IllegalStateException {
  /** Creates a gap diagnostic for one source position. */
  public QueryProjectionGapException(String message) {
    super(message);
  }
}
