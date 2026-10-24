package com.simplematch.queryservice.web;

import com.simplematch.queryservice.runtime.QueryAuthorizedReadService;
import com.simplematch.queryservice.security.QueryPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes account reads through the authorized PostgreSQL application module. */
@RestController
@RequestMapping("/api/v1/accounts")
public final class QueryAccountReadController {
  private final QueryAuthorizedReadService reads;

  /** Creates the HTTP adapter without a cache dependency. */
  public QueryAccountReadController(QueryAuthorizedReadService reads) {
    this.reads = reads;
  }

  /** Returns an explicitly authorized reservation-related account projection. */
  @GetMapping("/{accountId}/summary")
  public ResponseEntity<?> accountSummary(
      @PathVariable String accountId, @AuthenticationPrincipal QueryPrincipal principal) {
    final var canonicalAccountId = QueryResourceIds.canonical(accountId);
    if (canonicalAccountId.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }
    return reads.accountSummary(principal, canonicalAccountId.orElseThrow())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
