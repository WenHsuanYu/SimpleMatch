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
import com.simplematch.queryservice.model.QueryOrderView;
import com.simplematch.queryservice.runtime.QueryReadCache;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QueryReadControllerTest {
  private static final UUID ORDER_ID = UUID.fromString("0198a001-0000-7000-8000-000000000002");
  private static final QueryOrderView ORDER =
      new QueryOrderView(
          ORDER_ID.toString(), "0198a001-0000-7000-8000-000000000003", "XTAI", "2330",
          "SIDE_BUY", "RESTING", 10, "event-1", 1);
  private static final QueryFreshness FRESHNESS = new QueryFreshness(List.of());

  @Test
  @DisplayName("Redis read failures fall back to the durable order projection")
  void redisReadFailureFallsBackToPostgres() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    when(store.findOrder(ORDER_ID.toString())).thenReturn(Optional.of(ORDER));
    when(store.freshness()).thenReturn(FRESHNESS);
    final QueryReadCache cache = new FailingCache(true, false);

    final QueryReadController controller =
        new QueryReadController(store, provider(cache), properties());

    assertThat(controller.order(ORDER_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(controller.order(ORDER_ID).getBody()).isNotNull();
  }

  @Test
  @DisplayName("Redis write failures do not fail a successful durable read")
  void redisWriteFailureDoesNotFailPostgresRead() {
    final QueryProjectionStore store = mock(QueryProjectionStore.class);
    when(store.findOrder(ORDER_ID.toString())).thenReturn(Optional.of(ORDER));
    when(store.freshness()).thenReturn(FRESHNESS);
    final QueryReadCache cache = new FailingCache(false, true);

    final QueryReadController controller =
        new QueryReadController(store, provider(cache), properties());

    assertThat(controller.order(ORDER_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/orders/not-a-uuid", "/api/v1/orders/not-a-uuid/executions",
      "/api/v1/accounts/not-a-uuid/summary"})
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
