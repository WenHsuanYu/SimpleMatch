package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.model.QueryAccountSummaryView;
import com.simplematch.queryservice.model.QueryOrderView;
import com.simplematch.queryservice.security.QueryPrincipal;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns explicit account authorization and short durable read transactions without a cache path. */
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
            status -> store.findOrder(canonicalOrderId)
                .flatMap(order -> authorizedOrder(principal, canonicalOrderId, order))));
  }

  private Optional<QueryReadResponse<QueryOrderView>> authorizedOrder(
      QueryPrincipal principal, String orderId, QueryOrderView order) {
    if (!orderId.equals(order.orderId())) {
      throw inconsistentOrderIdentity();
    }
    final UUID owner = trustedOrderOwner(order.accountId());
    if (!principal.accountGrants().contains(owner)) {
      return Optional.empty();
    }
    return Optional.of(QueryReadResponse.fromProjection(order, store.freshness()));
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
