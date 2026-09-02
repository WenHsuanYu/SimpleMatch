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
import org.springframework.jdbc.core.JdbcOperations;

/** JDBC adapter that keeps PostgreSQL durable state ahead of the optional Redis read cache. */
public final class JdbcQueryProjectionStore implements QueryProjectionStore {
  private final JdbcOperations jdbcTemplate;

  /** Creates the query-service JDBC adapter over the service-owned DataSource. */
  public JdbcQueryProjectionStore(JdbcOperations jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void projectMatching(
      FinalMatchingEventEnvelope envelope, QueryProjectionSource source) {
    final QueryProjectionPosition position = source.position();
    final String eventId = envelope.eventIdHex();
    if (!JdbcQueryProjectionState.claimInbox(
        jdbcTemplate, eventId, source.topic(), envelope.payloadSha256(), position)) {
      return;
    }
    JdbcQueryProjectionState.assertContiguous(jdbcTemplate, source.topic(), position);
    JdbcQueryMatchingProjection.project(jdbcTemplate, envelope.event(), eventId, position);
    JdbcQueryProjectionState.advance(jdbcTemplate, source.topic(), position);
  }

  @Override
  public void projectAccountLifecycle(
      AccountLifecycleEvent event,
      byte[] rawPayload,
      QueryProjectionSource source) {
    final String eventId = event.getMetadata().getEventId();
    if (eventId.isBlank()) {
      throw new IllegalArgumentException("account lifecycle event_id is required");
    }
    final QueryProjectionPosition position = source.position();
    if (!JdbcQueryProjectionState.claimInbox(
        jdbcTemplate,
        eventId,
        source.topic(),
        FinalMatchingEventEnvelope.sha256(rawPayload),
        position)) {
      return;
    }
    JdbcQueryProjectionState.assertContiguous(jdbcTemplate, source.topic(), position);
    JdbcQueryAccountProjection.project(jdbcTemplate, event, eventId, position);
    JdbcQueryProjectionState.advance(jdbcTemplate, source.topic(), position);
  }

  @Override
  public void installMarketReference(
      VerifiedMarketReferenceArtifact artifact, long installedAtUnixMs) {
    JdbcQueryMarketReferenceProjection.install(jdbcTemplate, artifact, installedAtUnixMs);
  }

  @Override
  public void markRecoveryRequired(QueryProjectionSource source) {
    JdbcQueryProjectionState.markRecoveryRequired(
        jdbcTemplate, source.topic(), source.position());
  }

  @Override
  public Optional<QueryOrderView> findOrder(String orderId) {
    return JdbcQueryProjectionReads.findOrder(jdbcTemplate, orderId);
  }

  @Override
  public List<QueryExecutionView> findExecutions(String orderId) {
    return JdbcQueryProjectionReads.findExecutions(jdbcTemplate, orderId);
  }

  @Override
  public Optional<QueryAccountSummaryView> findAccountSummary(String accountId) {
    return JdbcQueryProjectionReads.findAccountSummary(jdbcTemplate, accountId);
  }

  @Override
  public Optional<QueryMarketReferenceView> findMarketReference(
      String tradingDay, String venueMic, String symbol) {
    return JdbcQueryProjectionReads.findMarketReference(
        jdbcTemplate, tradingDay, venueMic, symbol);
  }

  @Override
  public QueryFreshness freshness() {
    return JdbcQueryProjectionReads.freshness(jdbcTemplate);
  }

  @Override
  public void resetForReplay() {
    JdbcQueryProjectionState.resetForReplay(jdbcTemplate);
  }
}
