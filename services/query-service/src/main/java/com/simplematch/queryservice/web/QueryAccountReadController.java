package com.simplematch.queryservice.web;

import com.simplematch.queryservice.runtime.QueryAuthorizedReadService;
import com.simplematch.queryservice.security.QueryPrincipal;
import java.util.UUID;
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
    final UUID canonicalAccountId;
    try {
      canonicalAccountId = UUID.fromString(accountId);
    } catch (IllegalArgumentException malformed) {
      return ResponseEntity.badRequest().build();
    }
    if (!canonicalAccountId.toString().equals(accountId)) {
      return ResponseEntity.badRequest().build();
    }
    return reads.accountSummary(principal, canonicalAccountId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
