package com.simplematch.quickfixgateway.operations.live;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded internal HTTP JSON reader used by the live observation adapter. */
@FunctionalInterface
public interface StatusDocumentClient {
  /** Reads and parses one required JSON status document. */
  JsonNode read(String endpoint);
}
