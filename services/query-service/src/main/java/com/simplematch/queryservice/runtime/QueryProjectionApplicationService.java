package com.simplematch.queryservice.runtime;

import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.store.QueryProjectionStore;
import org.springframework.transaction.annotation.Transactional;

/** Owns one atomic PostgreSQL projection outcome for each asynchronous source fact. */
public class QueryProjectionApplicationService {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private final QueryProjectionStore store;

  /** Creates the application service over the durable query projection port. */
  public QueryProjectionApplicationService(QueryProjectionStore store) {
    this.store = store;
  }

  /** Projects one final Matching Event, inbox claim, model update, and checkpoint atomically. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public void projectMatching(
      FinalMatchingEventEnvelope envelope, int partition, long offset, long observedAtUnixMs) {
    store.projectMatching(envelope, partition, offset, observedAtUnixMs);
  }

  /** Projects one Account lifecycle fact, model update, and checkpoint atomically. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public void projectAccountLifecycle(
      AccountLifecycleEvent event,
      byte[] rawPayload,
      int partition,
      long offset,
      long observedAtUnixMs) {
    store.projectAccountLifecycle(event, rawPayload, partition, offset, observedAtUnixMs);
  }

  /** Installs a verified immutable market-reference artifact in one local transaction. */
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public void installMarketReference(
      VerifiedMarketReferenceArtifact artifact, long installedAtUnixMs) {
    store.installMarketReference(artifact, installedAtUnixMs);
  }
}
