package com.simplematch.queryservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.queryservice.runtime.QueryReadCache;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Seeds obsolete external-cache responses and records every attempted cache operation. */
final class QueryReadCacheProbe implements QueryReadCache {
  private final Map<String, JsonNode> entries = new HashMap<>();
  private int reads;
  private int writes;
  private int clears;

  void reset() {
    entries.clear();
    reads = 0;
    writes = 0;
    clears = 0;
  }

  void seedLegacyAccount(String selectedAccount, String payloadAccount) {
    final var response = legacyEnvelope();
    response.putObject("data").put("accountId", payloadAccount).put("reservedNotionalUnits", 9999);
    entries.put("query:v1:account-summary:" + selectedAccount, response);
  }

  void seedLegacyOrder(String selectedOrder, String payloadOrder, String payloadAccount) {
    final var response = legacyEnvelope();
    response.putObject("data").put("orderId", payloadOrder).put("accountId", payloadAccount)
        .put("state", "RESTING").put("leavesQuantityShares", 10);
    entries.put("query:v1:order:" + selectedOrder, response);
  }

  private com.fasterxml.jackson.databind.node.ObjectNode legacyEnvelope() {
    final var response = new ObjectMapper().createObjectNode();
    response.putObject("freshness").putArray("partitions").addObject()
        .put("sourceTopic", "legacy.cached.topic").put("lastProcessedOffset", 999);
    return response;
  }

  boolean untouched() {
    return reads == 0 && writes == 0 && clears == 0;
  }

  int entries() {
    return entries.size();
  }

  /** Returns seeded bytes if a protected read accidentally reaches the external-cache port. */
  @Override
  public Optional<JsonNode> get(String key) {
    reads++;
    return Optional.ofNullable(entries.get(key));
  }

  /** Records an attempted cache fill independently of response correctness. */
  @Override
  public void put(String key, Object value) {
    writes++;
  }

  /** Records broad cleanup so bypass cannot be implemented by deleting cache contents. */
  @Override
  public void clear() {
    clears++;
    entries.clear();
  }
}
