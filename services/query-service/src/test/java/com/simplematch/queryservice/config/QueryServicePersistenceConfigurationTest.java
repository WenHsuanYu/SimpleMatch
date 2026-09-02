package com.simplematch.queryservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.store.QueryProjectionStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class QueryServicePersistenceConfigurationTest {
  @Test
  void boundsOperatorReplayTransaction() {
    final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadCache cache = mock(QueryReadCache.class);
    final QueryProjectionRebuildService rebuildService =
        new QueryServicePersistenceConfiguration()
            .queryProjectionRebuildService(store, transactionManager, cache);

    rebuildService.resetForReplay();

    assertThat(transactionManager.timeoutSeconds).isEqualTo(8);
    assertThat(transactionManager.committed).isTrue();
  }

  @Test
  void rollsBackBoundedReplayTransactionWhenResetFails() {
    final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadCache cache = mock(QueryReadCache.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(store)
        .resetForReplay();
    final QueryProjectionRebuildService rebuildService =
        new QueryServicePersistenceConfiguration()
            .queryProjectionRebuildService(store, transactionManager, cache);

    assertThatThrownBy(rebuildService::resetForReplay)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");

    assertThat(transactionManager.timeoutSeconds).isEqualTo(8);
    assertThat(transactionManager.committed).isFalse();
    assertThat(transactionManager.rolledBack).isTrue();
  }

  private static final class RecordingTransactionManager
      implements PlatformTransactionManager {
    private int timeoutSeconds;
    private boolean committed;
    private boolean rolledBack;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      timeoutSeconds = definition.getTimeout();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      committed = true;
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      rolledBack = true;
    }
  }
}
