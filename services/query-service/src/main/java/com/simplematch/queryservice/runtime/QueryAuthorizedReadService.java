package com.simplematch.queryservice.runtime;

import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.queryservice.model.QueryAccountSummaryView;
import com.simplematch.queryservice.model.QueryExecutionView;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.model.QueryOrderView;
import com.simplematch.queryservice.security.QueryPrincipal;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns Account authorization and short durable business-read snapshots without a cache path. */
public final class QueryAuthorizedReadService {
  private static final int READ_TRANSACTION_TIMEOUT_SECONDS = 5;
  private final QueryProjectionStore store;
  private final TransactionTemplate readSnapshot;

  /** Creates the shared short repeatable-read boundary for Query-owned durable responses. */
  public QueryAuthorizedReadService(
      QueryProjectionStore store, PlatformTransactionManager transactionManager) {
    this.store = store;
    readSnapshot = new TransactionTemplate(transactionManager);
    readSnapshot.setReadOnly(true);
    readSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readSnapshot.setTimeout(READ_TRANSACTION_TIMEOUT_SECONDS);
  }

  /**
   * Reads reservation-related state only for an explicitly entitled principal.
   *
   * @param principal trusted identity and grants from the authenticated policy snapshot
   * @param accountId canonical requested account identity
   * @return the authorized projection, or an indistinguishable denied/missing result
   * @throws DataIntegrityViolationException if the projection returns a different account
   */
  public Optional<QueryReadResponse<QueryAccountSummaryView>> accountSummary(
      QueryPrincipal principal, UUID accountId) {
    if (!principal.accountGrants().contains(accountId)) {
      return Optional.empty();
    }
    final String canonicalAccountId = accountId.toString();
    return Objects.requireNonNull(
        readSnapshot.execute(
            status ->
                store.findAccountSummary(canonicalAccountId)
                    .map(summary -> authorizedSummary(canonicalAccountId, summary))));
  }

  /**
   * Authorizes an Order using ownership and details from the same durable row and read snapshot.
   *
   * @param principal trusted identity and grants from the authenticated policy snapshot
   * @param orderId canonical requested Order identity
   * @return the authorized Order, or an indistinguishable denied/missing result
   * @throws DataIntegrityViolationException if the projection identities are inconsistent
   */
  public Optional<QueryReadResponse<QueryOrderView>> order(QueryPrincipal principal, UUID orderId) {
    final String canonicalOrderId = orderId.toString();
    return Objects.requireNonNull(
        readSnapshot.execute(
            status -> authorizedParent(principal, canonicalOrderId)
                .map(order -> QueryReadResponse.fromProjection(order, store.freshness()))));
  }

  /**
   * Reads executions only for an existing parent whose durable Account is explicitly granted.
   *
   * @param principal trusted identity and grants from the authenticated policy snapshot
   * @param orderId canonical requested parent Order identity
   * @return the authorized collection, or an indistinguishable denied/missing parent result
   * @throws DataIntegrityViolationException if any parent or execution identity is inconsistent
   */
  public Optional<QueryReadResponse<List<QueryExecutionView>>> executions(
      QueryPrincipal principal, UUID orderId) {
    final String canonicalOrderId = orderId.toString();
    return Objects.requireNonNull(
        readSnapshot.execute(
            status -> authorizedParent(principal, canonicalOrderId)
                .map(this::authorizedExecutions)));
  }

  /**
   * Reads installed artifact facts for the authenticated Market route without account grants.
   *
   * @param tradingDay requested artifact trading day, independent of the wall clock
   * @param instrument validated canonical artifact instrument identity
   * @return the installed row and snapshot-associated diagnostics, or a missing result
   * @throws DataIntegrityViolationException if the returned artifact row targets another resource
   */
  public Optional<QueryReadResponse<QueryMarketReferenceView>> marketReference(
      LocalDate tradingDay, InstrumentRef instrument) {
    return Objects.requireNonNull(
        readSnapshot.execute(status -> store.findMarketReference(
                tradingDay.toString(), instrument.venueMic(), instrument.symbol())
            .map(reference -> {
              if (!tradingDay.equals(reference.tradingDay())
                  || !instrument.venueMic().equals(reference.venueMic())
                  || !instrument.symbol().equals(reference.symbol())) {
                throw new DataIntegrityViolationException(
                    "Query market-reference projection identity is inconsistent");
              }
              return QueryReadResponse.fromProjection(reference, store.freshness());
            })));
  }

  private QueryReadResponse<List<QueryExecutionView>> authorizedExecutions(QueryOrderView parent) {
    final List<QueryExecutionView> executions = store.findExecutions(parent.orderId());
    for (QueryExecutionView execution : executions) {
      if (!parent.orderId().equals(execution.orderId())
          || !parent.accountId().equals(execution.accountId())) {
        throw new DataIntegrityViolationException(
            "Query execution projection identity is inconsistent");
      }
    }
    return QueryReadResponse.fromProjection(List.copyOf(executions), store.freshness());
  }

  private Optional<QueryOrderView> authorizedParent(QueryPrincipal principal, String orderId) {
    return store.findOrder(orderId).flatMap(order -> authorizedOrder(principal, orderId, order));
  }

  private Optional<QueryOrderView> authorizedOrder(
      QueryPrincipal principal, String orderId, QueryOrderView order) {
    if (!orderId.equals(order.orderId())) {
      throw inconsistentOrderIdentity();
    }
    final UUID owner = trustedOrderOwner(order.accountId());
    if (!principal.accountGrants().contains(owner)) {
      return Optional.empty();
    }
    return Optional.of(order);
  }

  private static UUID trustedOrderOwner(String accountId) {
    if (accountId == null) {
      throw inconsistentOrderIdentity();
    }
    final UUID owner;
    try {
      owner = UUID.fromString(accountId);
    } catch (IllegalArgumentException malformed) {
      throw inconsistentOrderIdentity();
    }
    if (!owner.toString().equals(accountId)) {
      throw inconsistentOrderIdentity();
    }
    return owner;
  }

  private static DataIntegrityViolationException inconsistentOrderIdentity() {
    return new DataIntegrityViolationException("Query order projection identity is inconsistent");
  }

  private QueryReadResponse<QueryAccountSummaryView> authorizedSummary(
      String accountId, QueryAccountSummaryView summary) {
    if (!accountId.equals(summary.accountId())) {
      throw new DataIntegrityViolationException(
          "Query account projection identity is inconsistent");
    }
    return QueryReadResponse.fromProjection(summary, store.freshness());
  }
}
