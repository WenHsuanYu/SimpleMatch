package com.simplematch.queryservice.runtime;

import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator seam for clearing and rebuilding non-critical query read models. */
public final class QueryProjectionRebuildService {
  private final QueryProjectionStore store;
  private final TransactionTemplate transactionTemplate;
  private final QueryReadCache cache;

  /** Creates the rebuild operation over the query service's local transaction manager. */
  public QueryProjectionRebuildService(
      QueryProjectionStore store,
      TransactionTemplate transactionTemplate,
      QueryReadCache cache) {
    this.store = Objects.requireNonNull(store, "store");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Clears reconstructible projections and checkpoints before an operator resets Kafka offsets.
   *
   * <p>The durable PostgreSQL reset commits before the disposable Redis namespace is cleared.
   * Redis failure is propagated after that commit so the operator can retry cache reconciliation
   * without rolling back the durable reset.
   */
  public void resetForReplay() {
    transactionTemplate.executeWithoutResult(ignored -> store.resetForReplay());
    cache.clear();
  }

  /** Installs a checked final artifact after a replay reset or a trading-day cutover. */
  public void installMarketReference(
      VerifiedMarketReferenceArtifact artifact, long installedAtUnixMs) {
    transactionTemplate.executeWithoutResult(
        ignored -> store.installMarketReference(artifact, installedAtUnixMs));
  }
}
