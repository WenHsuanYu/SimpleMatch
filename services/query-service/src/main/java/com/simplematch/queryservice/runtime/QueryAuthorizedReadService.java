package com.simplematch.queryservice.runtime;

import com.simplematch.queryservice.model.QueryAccountSummaryView;
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

  /** Creates a repeatable-read snapshot that begins only after account authorization. */
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

  private QueryReadResponse<QueryAccountSummaryView> authorizedSummary(
      String accountId, QueryAccountSummaryView summary) {
    if (!accountId.equals(summary.accountId())) {
      throw new DataIntegrityViolationException(
          "Query account projection identity is inconsistent");
    }
    return QueryReadResponse.fromProjection(summary, store.freshness());
  }
}
