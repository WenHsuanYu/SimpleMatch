package com.simplematch.queryservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.simplematch.queryservice.config.QueryServiceProperties;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.model.QueryMarketReferenceView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueryReadControllerTest {
  private static final QueryMarketReferenceView REFERENCE = new QueryMarketReferenceView(
      LocalDate.of(2026, 8, 27), "fixture-artifact", "XTAI", "2330", "rule",
      100L, 90L, 110L, 0, 1L);
  private static final QueryFreshness FRESHNESS = new QueryFreshness(List.of());

  @Test
  @DisplayName("Legacy Market adapter cache read failures retain its durable result")
  void redisReadFailureFallsBackToPostgres() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    when(store.findMarketReference("2026-08-27", "XTAI", "2330"))
        .thenReturn(Optional.of(REFERENCE));
    when(store.freshness()).thenReturn(FRESHNESS);
    final QueryReadCache cache = new FailingCache(true, false);

    final QueryReadController controller =
        new QueryReadController(store, provider(cache), properties());

    assertThat(controller.marketReference("2026-08-27", "XTAI", "2330").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(controller.marketReference("2026-08-27", "XTAI", "2330").getBody()).isNotNull();
  }

  @Test
  @DisplayName("Redis write failures do not fail a successful durable read")
  void redisWriteFailureDoesNotFailPostgresRead() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    when(store.findMarketReference("2026-08-27", "XTAI", "2330"))
        .thenReturn(Optional.of(REFERENCE));
    when(store.freshness()).thenReturn(FRESHNESS);
    final QueryReadCache cache = new FailingCache(false, true);

    final QueryReadController controller =
        new QueryReadController(store, provider(cache), properties());

    assertThat(controller.marketReference("2026-08-27", "XTAI", "2330").getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/orders/not-a-uuid/executions"})
  void rejectsMalformedUuidBeforeDurableRead(String path) throws Exception {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    final QueryReadController controller = new QueryReadController(
        store, provider(new FailingCache(false, false)), properties());

    MockMvcBuilders.standaloneSetup(controller).build().perform(get(path))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(store);
  }

  private QueryServiceProperties properties() {
    return new QueryServiceProperties(null, null, null, null, null);
  }

  private ObjectProvider<QueryReadCache> provider(QueryReadCache cache) {
    final ObjectProvider<QueryReadCache> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(cache);
    return provider;
  }

  private static final class FailingCache implements QueryReadCache {
    private final boolean failRead;
    private final boolean failWrite;

    private FailingCache(boolean failRead, boolean failWrite) {
      this.failRead = failRead;
      this.failWrite = failWrite;
    }

    @Override
    public Optional<JsonNode> get(String key) {
      if (failRead) {
        throw new IllegalStateException("Redis is unavailable");
      }
      return Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
      if (failWrite) {
        throw new IllegalStateException("Redis is unavailable");
      }
    }

    @Override
    public void clear() {}
  }
}
