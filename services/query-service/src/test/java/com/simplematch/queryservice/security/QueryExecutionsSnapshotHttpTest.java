package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** PostgreSQL snapshot evidence only; this does not prove immediate ownership-change revocation. */
@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
@EnabledIfSystemProperty(named = "simplematch.query.jdbc.backend", matches = "postgresql")
class QueryExecutionsSnapshotHttpTest {
  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionApplicationService projections;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private QueryReadTestDataSource dataSource;
  @Autowired private QueryReadCacheProbe cache;
  @Autowired private PlatformTransactionManager transactionManager;
  private MockMvc http;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    QueryOrderHttpFixtures.POLICY.register(registry);
  }

  @BeforeEach
  void prepareOwnedFixtures() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    dataSource.resetControls();
    cache.reset();
    store.resetForReplay();
    QueryAccountHttpFixtures.projectAccounts(projections);
    QueryOrderHttpFixtures.seed(jdbc);
  }

  @AfterEach
  void clearOwnedFixtures() {
    dataSource.resetControls();
    store.resetForReplay();
  }

  @Test
  void concurrentParentAndExecutionUpdateCannotMixOwnershipPayloadOrDiagnostics() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    final var newOwner = QueryOrderHttpFixtures.Y_EXECUTED.accountId();
    completeReadAcrossWrite(false, () -> {
      jdbc.update("UPDATE query_service.order_read_model SET account_id = ? WHERE order_id = ?",
          newOwner, parent.orderId());
      jdbc.update("UPDATE query_service.execution_read_model SET account_id = ?, "
          + "fill_quantity_shares = 6, cumulative_quantity_shares = 6 WHERE order_id = ?",
          newOwner, parent.orderId());
      store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
    });
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].accountId").value(newOwner.toString()))
        .andExpect(jsonPath("$.data[0].fillQuantityShares").value(6))
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"));
  }

  @Test
  void parentDeletionAfterCollectionReadCannotMixNewDiagnosticsIntoOldData() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    completeReadAcrossWrite(true, () -> {
      jdbc.update("DELETE FROM query_service.execution_read_model WHERE order_id = ?", parent.orderId());
      jdbc.update("DELETE FROM query_service.order_read_model WHERE order_id = ?", parent.orderId());
      store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
    });
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  private void completeReadAcrossWrite(boolean pauseAfterExecutions, Runnable write) throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    final CountDownLatch pausedRead = new CountDownLatch(1);
    final CountDownLatch committed = new CountDownLatch(1);
    final Runnable pause = () -> {
      pausedRead.countDown();
      awaitWriter(committed);
    };
    if (pauseAfterExecutions) {
      dataSource.afterExecutionsRead(pause);
    } else {
      dataSource.afterOrderRead(pause);
    }
    try (var executor = Executors.newSingleThreadExecutor()) {
      final var inFlight = executor.submit(() -> http.perform(
          get("/api/v1/orders/{id}/executions", parent.orderId())
              .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
          .andExpect(status().isOk()).andReturn());
      try {
        assertThat(pausedRead.await(3, TimeUnit.SECONDS)).isTrue();
        final TransactionTemplate writer = new TransactionTemplate(transactionManager);
        writer.setTimeout(5);
        writer.executeWithoutResult(status -> write.run());
      } finally {
        committed.countDown();
      }
      final var body = inFlight.get(5, TimeUnit.SECONDS).getResponse().getContentAsString();
      final var json = new ObjectMapper().readTree(body);
      assertThat(json.at("/data").size()).isEqualTo(1);
      assertThat(json.at("/data/0/orderId").asText()).isEqualTo(parent.orderId().toString());
      assertThat(json.at("/data/0/accountId").asText()).isEqualTo(parent.accountId().toString());
      assertThat(json.at("/data/0/fillQuantityShares").asLong()).isEqualTo(5);
      assertThat(json.at("/freshness/projectionState").asText()).isEqualTo("NO_GAP_RECORDED");
      assertThat(json.at("/freshness").size()).isEqualTo(1);
    }
    assertThat(cache.untouched()).isTrue();
  }

  private static void awaitWriter(CountDownLatch committed) {
    try {
      if (!committed.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent test writer did not finish");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent execution probe was interrupted", interrupted);
    }
  }
}
