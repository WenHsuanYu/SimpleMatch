package com.simplematch.queryservice.store;

import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.model.QueryAccountSummaryView;
import com.simplematch.queryservice.model.QueryExecutionView;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.model.QueryOrderView;
import java.util.List;
import java.util.Optional;

/** Durable PostgreSQL port for rebuildable query read models and source checkpoints. */
public interface QueryProjectionStore {
  /** Applies one final Matching Event and its exact source position. */
  void projectMatching(FinalMatchingEventEnvelope envelope, QueryProjectionSource source);

  /** Applies one Account lifecycle fact and its exact source position. */
  void projectAccountLifecycle(
      AccountLifecycleEvent event, byte[] rawPayload, QueryProjectionSource source);

  /** Replaces the active market-reference rows for one verified artifact. */
  void installMarketReference(VerifiedMarketReferenceArtifact artifact, long installedAtUnixMs);

  /** Records a durable source gap so operators can reset and replay the affected projection. */
  void markRecoveryRequired(QueryProjectionSource source);

  /** Reads the durable order projection. */
  Optional<QueryOrderView> findOrder(String orderId);

  /** Reads executions in deterministic event order. */
  List<QueryExecutionView> findExecutions(String orderId);

  /** Reads the latest Account lifecycle summary. */
  Optional<QueryAccountSummaryView> findAccountSummary(String accountId);

  /** Reads one active market-reference row. */
  Optional<QueryMarketReferenceView> findMarketReference(
      String tradingDay, String venueMic, String symbol);

  /** Reads durable checkpoint freshness for all consumed sources. */
  QueryFreshness freshness();

  /** Clears all reconstructible read-side state before an operator replay. */
  void resetForReplay();
}
