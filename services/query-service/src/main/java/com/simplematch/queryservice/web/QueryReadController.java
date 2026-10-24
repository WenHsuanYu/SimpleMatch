package com.simplematch.queryservice.web;

import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.MarketReferenceValidationException;
import com.simplematch.queryservice.runtime.QueryAuthorizedReadService;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes authenticated Market reads and restricted operational checkpoints from PostgreSQL. */
@RestController
@RequestMapping("/api/v1")
public final class QueryReadController {
  private final QueryProjectionStore store;
  private final QueryAuthorizedReadService reads;

  /** Creates the query API without a Kafka dependency or critical-path callback. */
  public QueryReadController(QueryProjectionStore store, QueryAuthorizedReadService reads) {
    this.store = store;
    this.reads = reads;
  }

  /** Returns one active market-reference row by trading day and venue-qualified instrument. */
  @GetMapping("/market-reference/{tradingDay}/{venueMic}/{symbol}")
  public ResponseEntity<?> marketReference(
      @PathVariable String tradingDay,
      @PathVariable String venueMic,
      @PathVariable String symbol) {
    final LocalDate day;
    final InstrumentRef instrument;
    try {
      day = LocalDate.parse(tradingDay);
      if (!day.toString().equals(tradingDay)) {
        return ResponseEntity.badRequest().build();
      }
      instrument = new InstrumentRef(venueMic, symbol);
    } catch (DateTimeParseException | MarketReferenceValidationException malformed) {
      return ResponseEntity.badRequest().build();
    }
    return reads.marketReference(day, instrument)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Returns source checkpoints and explicit gap/replay state. */
  @GetMapping("/freshness")
  public ResponseEntity<?> freshness() {
    return ResponseEntity.ok(store.freshness());
  }
}
