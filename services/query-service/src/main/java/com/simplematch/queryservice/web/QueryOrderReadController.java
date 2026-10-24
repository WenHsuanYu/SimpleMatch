package com.simplematch.queryservice.web;

import com.simplematch.queryservice.runtime.QueryAuthorizedReadService;
import com.simplematch.queryservice.security.QueryPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes Order reads through trusted durable ownership and explicit Account Grants. */
@RestController
@RequestMapping("/api/v1/orders")
public final class QueryOrderReadController {
  private final QueryAuthorizedReadService reads;

  /** Creates the HTTP adapter without a response-cache dependency. */
  public QueryOrderReadController(QueryAuthorizedReadService reads) {
    this.reads = reads;
  }

  /** Returns an Order only when the authenticated principal is entitled to its durable owner. */
  @GetMapping("/{orderId}")
  public ResponseEntity<?> order(
      @PathVariable String orderId, @AuthenticationPrincipal QueryPrincipal principal) {
    final var canonicalOrderId = QueryResourceIds.canonical(orderId);
    if (canonicalOrderId.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    return reads.order(principal, canonicalOrderId.orElseThrow())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
