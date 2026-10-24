package com.simplematch.queryservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.queryservice.runtime.QueryReadCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Seeds obsolete external-cache responses and records every attempted cache operation. */
final class QueryReadCacheProbe implements QueryReadCache {
  private final Map<String, byte[]> entries = new HashMap<>();
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
    seedJson("query:v1:account-summary:" + selectedAccount, response);
  }

  void seedLegacyOrder(String selectedOrder, String payloadOrder, String payloadAccount) {
    final var response = legacyEnvelope();
    response.putObject("data").put("orderId", payloadOrder).put("accountId", payloadAccount)
        .put("state", "RESTING").put("leavesQuantityShares", 10);
    seedJson("query:v1:order:" + selectedOrder, response);
  }

  void seedLegacyExecutions(String selectedOrder, String payloadOrder, String payloadAccount) {
    final var response = legacyEnvelope();
    response.putArray("data").addObject().put("orderId", payloadOrder)
        .put("accountId", payloadAccount).put("fillQuantityShares", 9999);
    seedJson("query:v1:executions:" + selectedOrder, response);
  }

  void seedLegacyMarket(LocalDate day, InstrumentRef selected, InstrumentRef payload) {
    final var response = legacyEnvelope();
    response.putObject("data").put("tradingDay", day.toString())
        .put("venueMic", payload.venueMic()).put("symbol", payload.symbol())
        .put("artifactId", "obsolete-cached-artifact").put("referencePriceUnits", 9999);
    seedJson(marketKey(day, selected), response);
  }

  void seedMalformedMarket(LocalDate day, InstrumentRef selected) {
    entries.put(marketKey(day, selected), "{invalid JSON".getBytes(StandardCharsets.UTF_8));
  }

  private String marketKey(LocalDate day, InstrumentRef instrument) {
    return "query:v1:market-reference:" + day + ":" + instrument.venueMic() + ":" + instrument.symbol();
  }

  private void seedJson(String key, JsonNode response) {
    entries.put(key, response.toString().getBytes(StandardCharsets.UTF_8));
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
    final byte[] bytes = entries.get(key);
    if (bytes == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ObjectMapper().readTree(bytes));
    } catch (IOException malformed) {
      throw new UncheckedIOException(malformed);
    }
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
