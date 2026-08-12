package com.simplematch.marketdataprojection.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.marketdataprojection.config.MarketDataProjectionProperties;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCache;
import com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class MarketDataProjectionRebuildControllerTest {
  private static final String TOKEN = "projection-token";

  @Test
  void rejectsAnUnauthenticatedReset() {
    final TestStore store = new TestStore();
    final MarketDataProjectionRebuildController controller = controller(store);

    assertThatThrownBy(() -> controller.reset(null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(store.resetCount).isZero();
  }

  @Test
  void resetsOnlyProjectionOwnedStateAfterAuthentication() {
    final TestStore store = new TestStore();
    final MarketDataProjectionRebuildController controller = controller(store);

    assertThat(controller.reset(TOKEN).status()).isEqualTo("RESET_COMPLETE");
    assertThat(store.resetCount).isEqualTo(1);
  }

  @Test
  void rollsBackTheLocalResetWhenCacheClearingFails() {
    final TestStore store = new TestStore();
    final NoOpTransactionManager transactionManager = new NoOpTransactionManager();
    final MarketDataSnapshotCache failingCache =
        new MarketDataSnapshotCache() {
          @Override
          public void put(MarketDataSnapshotCacheEntry entry) {}

          @Override
          public void clear() {
            throw new IllegalStateException("redis unavailable");
          }
        };
    final MarketDataProjectionRebuildService service =
        new MarketDataProjectionRebuildService(
            store,
            new TransactionTemplate(transactionManager),
            Optional.of(failingCache));

    assertThatThrownBy(service::resetForReplay)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("redis unavailable");
    assertThat(transactionManager.commits).isZero();
    assertThat(transactionManager.rollbacks).isEqualTo(1);
  }

  private static MarketDataProjectionRebuildController controller(TestStore store) {
    final MarketDataProjectionProperties properties =
        new MarketDataProjectionProperties(
            null, null, null, new MarketDataProjectionProperties.Rebuild(true, TOKEN));
    return new MarketDataProjectionRebuildController(
        new MarketDataProjectionRebuildService(
            store, new TransactionTemplate(new NoOpTransactionManager()), Optional.empty()),
        properties,
        () -> {});
  }

  private static final class NoOpTransactionManager implements PlatformTransactionManager {
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

  private static final class TestStore implements MarketDataProjectionStore {
    private int resetCount;

    @Override
    public com.simplematch.marketdataprojection.runtime.MarketDataProjectionResult project(
        com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope envelope,
        int kafkaPartition,
        long kafkaOffset,
        long observedAtUnixMs) {
      throw new UnsupportedOperationException("project is not used by this controller test");
    }

    @Override
    public void markResyncRequired(
        int kafkaPartition, long failedKafkaOffset, long observedAtUnixMs) {}

    @Override
    public List<com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord> pendingOutbox(
        int limit) {
      return List.of();
    }

    @Override
    public void markOutboxPublished(
        com.simplematch.marketdataprojection.kafka.MarketDataOutboxRecord record,
        long publishedAtUnixMs) {}

    @Override
    public List<com.simplematch.marketdataprojection.cache.MarketDataSnapshotCacheEntry>
        pendingRedisSnapshots(int limit) {
      return List.of();
    }

    @Override
    public void markRedisSnapshotCached(MarketDataSnapshotCacheEntry entry) {}

    @Override
    public void resetForReplay() {
      resetCount++;
    }
  }
}
