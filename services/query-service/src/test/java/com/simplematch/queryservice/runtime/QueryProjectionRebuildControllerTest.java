package com.simplematch.queryservice.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simplematch.queryservice.config.QueryServiceProperties;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class QueryProjectionRebuildControllerTest {
  private static final String TOKEN = "query-rebuild-token";

  @Test
  void rejectsEnabledRebuildWithoutOperatorToken() {
    assertThatThrownBy(
            () ->
                new QueryServiceProperties(
                    null,
                    null,
                    null,
                    null,
                    new QueryServiceProperties.Rebuild(true, " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operatorToken is required");
  }

  @Test
  void rejectsUnauthenticatedResetWithoutStoppingConsumers() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadCache cache = mock(QueryReadCache.class);
    final QueryProjectionConsumerControl consumerControl =
        mock(QueryProjectionConsumerControl.class);
    final QueryProjectionRebuildController controller =
        controller(store, cache, consumerControl, new RecordingTransactionManager());

    assertThatThrownBy(() -> controller.reset(null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(store, cache, consumerControl);
  }

  @Test
  void stopsBothConsumersBeforeResettingOwnedState() {
    final AtomicBoolean consumersStopped = new AtomicBoolean();
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadCache cache = mock(QueryReadCache.class);
    final QueryProjectionConsumerControl consumerControl =
        mock(QueryProjectionConsumerControl.class);
    doAnswer(
            ignored -> {
              consumersStopped.set(true);
              return null;
            })
        .when(consumerControl)
        .stop();
    doAnswer(
            ignored -> {
              assertThat(consumersStopped).isTrue();
              return null;
            })
        .when(store)
        .resetForReplay();
    final RecordingTransactionManager transactionManager =
        new RecordingTransactionManager();
    final QueryProjectionRebuildController controller =
        controller(store, cache, consumerControl, transactionManager);

    assertThat(controller.reset(TOKEN).status()).isEqualTo("RESET_COMPLETE");
    verify(consumerControl).stop();
    verify(store).resetForReplay();
    verify(cache).clear();
    assertThat(transactionManager.commits).isEqualTo(1);
    assertThat(transactionManager.rollbacks).isZero();
  }

  @Test
  void rollsBackDurableResetWhenCacheClearingFails() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadCache cache = mock(QueryReadCache.class);
    final QueryProjectionConsumerControl consumerControl =
        mock(QueryProjectionConsumerControl.class);
    doThrow(new IllegalStateException("redis unavailable")).when(cache).clear();
    final RecordingTransactionManager transactionManager =
        new RecordingTransactionManager();
    final QueryProjectionRebuildController controller =
        controller(store, cache, consumerControl, transactionManager);

    assertThatThrownBy(() -> controller.reset(TOKEN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("redis unavailable");
    assertThat(transactionManager.commits).isZero();
    assertThat(transactionManager.rollbacks).isEqualTo(1);
  }

  private QueryProjectionRebuildController controller(
      QueryProjectionStore store,
      QueryReadCache cache,
      QueryProjectionConsumerControl consumerControl,
      PlatformTransactionManager transactionManager) {
    final QueryServiceProperties properties =
        new QueryServiceProperties(
            null,
            null,
            null,
            null,
            new QueryServiceProperties.Rebuild(true, TOKEN));
    return new QueryProjectionRebuildController(
        new QueryProjectionRebuildService(
            store, new TransactionTemplate(transactionManager), cache),
        properties,
        consumerControl);
  }

  private static final class RecordingTransactionManager
      implements PlatformTransactionManager {
    private int commits;
    private int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      rollbacks++;
    }
  }
}
