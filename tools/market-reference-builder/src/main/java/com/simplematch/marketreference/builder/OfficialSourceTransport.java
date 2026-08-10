package com.simplematch.marketreference.builder;

/** Infrastructure boundary that retrieves an exact official source document. */
@FunctionalInterface
public interface OfficialSourceTransport {
  /** Retrieves one required official source document. */
  RetrievedOfficialSource retrieve(OfficialSourceType sourceType);
}
