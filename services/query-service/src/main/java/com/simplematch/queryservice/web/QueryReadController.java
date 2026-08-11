package com.simplematch.queryservice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.queryservice.config.QueryServiceProperties;
import com.simplematch.queryservice.model.QueryExecutionView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.runtime.QueryReadResponse;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Versioned read-only APIs backed by PostgreSQL and optionally accelerated by Redis. */
@RestController
@RequestMapping("/api/v1")
public final class QueryReadController {
  private final QueryProjectionStore store;
  private final Supplier<QueryReadCache> cache;
  private final String cacheKeyPrefix;

  /** Creates the query API without a Kafka dependency or critical-path callback. */
  public QueryReadController(
      QueryProjectionStore store,
      ObjectProvider<QueryReadCache> cacheProvider,
      QueryServiceProperties properties) {
    this.store = store;
    this.cache = cacheProvider::getObject;
    this.cacheKeyPrefix = properties.redis().keyPrefix();
  }

  /** Returns one order read model with durable freshness metadata. */
  @GetMapping("/orders/{orderId}")
  public ResponseEntity<?> order(@PathVariable String orderId) {
    return read(
        cacheKey("order", orderId),
        store.findOrder(orderId).map(order -> new QueryReadResponse<>(order, store.freshness())));
  }

  /** Returns all executions for one order in deterministic source order. */
  @GetMapping("/orders/{orderId}/executions")
  public ResponseEntity<?> executions(@PathVariable String orderId) {
    return read(
        cacheKey("executions", orderId),
        Optional.of(
            new QueryReadResponse<List<QueryExecutionView>>(
                store.findExecutions(orderId), store.freshness())));
  }

  /** Returns the latest Account lifecycle summary. */
  @GetMapping("/accounts/{accountId}/summary")
  public ResponseEntity<?> accountSummary(@PathVariable String accountId) {
    return read(
        cacheKey("account-summary", accountId),
        store.findAccountSummary(accountId)
            .map(summary -> new QueryReadResponse<>(summary, store.freshness())));
  }

  /** Returns one active market-reference row by trading day and venue-qualified instrument. */
  @GetMapping("/market-reference/{tradingDay}/{venueMic}/{symbol}")
  public ResponseEntity<?> marketReference(
      @PathVariable String tradingDay,
      @PathVariable String venueMic,
      @PathVariable String symbol) {
    LocalDate.parse(tradingDay);
    return read(
        cacheKey("market-reference", tradingDay, venueMic, symbol),
        store.findMarketReference(
                tradingDay,
                venueMic.trim().toUpperCase(Locale.ROOT),
                symbol.trim().toUpperCase(Locale.ROOT))
            .map(reference -> new QueryReadResponse<>(reference, store.freshness())));
  }

  /** Returns source checkpoints and explicit gap/replay state. */
  @GetMapping("/freshness")
  public ResponseEntity<?> freshness() {
    return ResponseEntity.ok(store.freshness());
  }

  private ResponseEntity<?> read(String key, Optional<?> durableValue) {
    final Optional<JsonNode> cached = readCache(key);
    if (cached.isPresent()) {
      return ResponseEntity.ok(cached.get());
    }
    if (durableValue.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    writeCache(key, durableValue.get());
    return ResponseEntity.ok(durableValue.get());
  }

  private Optional<JsonNode> readCache(String key) {
    try {
      return cache.get().get(key);
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private void writeCache(String key, Object value) {
    try {
      cache.get().put(key, value);
    } catch (RuntimeException ignored) {
      // Redis is an acceleration layer; a cache outage must not fail a durable read.
    }
  }

  private String cacheKey(String type, String... values) {
    return cacheKeyPrefix + ":" + type + ":" + String.join(":", values);
  }
}
