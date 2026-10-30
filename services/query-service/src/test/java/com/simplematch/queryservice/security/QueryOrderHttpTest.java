package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
class QueryOrderHttpTest {
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
    assertThat(store.findOrder(QueryOrderHttpFixtures.MISSING_PARENT.toString())).isEmpty();
  }

  @AfterEach
  void clearOwnedFixtures() {
    dataSource.resetControls();
    store.resetForReplay();
  }

  @Test
  void grantedPrincipalReadsOrderOwnedByItsAccount() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(order.orderId().toString()))
        .andExpect(jsonPath("$.data.accountId").value(order.accountId().toString()))
        .andExpect(jsonPath("$.data.state").value("PARTIALLY_FILLED"))
        .andExpect(jsonPath("$.freshness.projectionState").value("NO_GAP_RECORDED"));
  }

  @ParameterizedTest
  @CsvSource({
    "client-a,X-with-executions,200", "client-a,X-empty,200",
    "client-a,Y-with-executions,404", "client-a,Y-empty,404",
    "client-b,X-with-executions,404", "client-b,X-empty,404",
    "client-b,Y-with-executions,200", "client-b,Y-empty,200",
    "client-c,X-with-executions,200", "client-c,X-empty,200",
    "client-c,Y-with-executions,200", "client-c,Y-empty,200",
    "operations,X-with-executions,404", "operations,X-empty,404",
    "operations,Y-with-executions,404", "operations,Y-empty,404",
    "client-d,X-with-executions,404", "client-d,X-empty,404",
    "client-d,Y-with-executions,404", "client-d,Y-empty,404"
  })
  void appliesAccountGrantsToTrustedOrderOwnership(String principal, String name, int expected)
      throws Exception {
    final var order = QueryOrderHttpFixtures.named(name);
    final var result = http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token(principal)))
        .andExpect(status().is(expected))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    if (expected == 200) {
      result.andExpect(jsonPath("$.data.orderId").value(order.orderId().toString()))
          .andExpect(jsonPath("$.data.accountId").value(order.accountId().toString()));
    } else {
      result.andExpect(content().string(""));
    }
  }

  @Test
  void missingOwnershipAndDeniedOrderHaveTheSameEmptyNotFoundContract() throws Exception {
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.MISSING_PARENT)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.Y_EXECUTED.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "0198A001-0000-7000-8000-000000000002"})
  void authenticatesBeforeCanonicalOrderUuidParsing(String supplied) throws Exception {
    http.perform(get("/api/v1/orders/{id}", supplied)).andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/orders/{id}", supplied)
        .header("Authorization", "Bearer " + new QuerySecurityFixtures().token("client-a")))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/orders/{id}", supplied)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isBadRequest()).andExpect(content().string(""));
  }

  @Test
  void clientAccountAndIdentitySelectorsCannotReplaceDurableOwnership() throws Exception {
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.Y_EXECUTED.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a"))
        .header("X-Principal", QueryOrderHttpFixtures.POLICY.principalId("client-b"))
        .header("X-Account-Id", QueryAccountHttpFixtures.X)
        .queryParam("accountId", QueryAccountHttpFixtures.X))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @Test
  void ignoresForeignAndStaleFullEnvelopesWithoutAnyCacheReadFillOrClear() throws Exception {
    final var selected = QueryOrderHttpFixtures.X_EXECUTED;
    final var foreign = QueryOrderHttpFixtures.Y_EXECUTED;
    cache.seedLegacyOrder(selected.orderId().toString(), foreign.orderId().toString(),
        foreign.accountId().toString());
    http.perform(get("/api/v1/orders/{id}", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orderId").value(selected.orderId().toString()))
        .andExpect(jsonPath("$.data.accountId").value(selected.accountId().toString()))
        .andExpect(jsonPath("$.data.state").value("PARTIALLY_FILLED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist())
        .andExpect(content().string(Matchers.not(Matchers.containsString(foreign.accountId().toString()))));
    cache.seedLegacyOrder(selected.orderId().toString(), selected.orderId().toString(),
        selected.accountId().toString());
    http.perform(get("/api/v1/orders/{id}", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("PARTIALLY_FILLED"));
    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(1);
  }

  @Test
  void cachedOrderCannotReplaceMissingOwnership() throws Exception {
    cache.seedLegacyOrder(QueryOrderHttpFixtures.MISSING_PARENT.toString(),
        QueryOrderHttpFixtures.MISSING_PARENT.toString(), QueryAccountHttpFixtures.X);
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.MISSING_PARENT)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void cachedOrderCannotReplaceUnavailableDurableOwnership() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    cache.seedLegacyOrder(order.orderId().toString(), order.orderId().toString(), order.accountId().toString());
    dataSource.unavailable(true);
    http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void returnedOrderIdentityMustMatchTheRequestedResource() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    dataSource.returnedOrderIdentity(QueryOrderHttpFixtures.Y_EXECUTED.orderId(), order.accountId());
    http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void accountOwnershipFromTheDurableReplyStillRequiresItsGrant() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    dataSource.returnedOrderIdentity(order.orderId(), QueryOrderHttpFixtures.Y_EXECUTED.accountId());
    http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS", "POST", "PUT", "DELETE"})
  void deniesUnapprovedOrderMethods(String method) throws Exception {
    http.perform(request(HttpMethod.valueOf(method), "/api/v1/orders/" + QueryOrderHttpFixtures.X_EMPTY.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isForbidden());
  }

  @Test
  void enablingOrderAndExecutionsDoesNotEnableUnknownSubroutes() throws Exception {
    for (String suffix : new String[] {"/unknown", "/executions/unknown"}) {
      http.perform(get("/api/v1/orders/" + QueryOrderHttpFixtures.X_EXECUTED.orderId() + suffix)
          .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void recordsVerifiedExecutedEmptyAndMissingFixtureRelationships() throws Exception {
    for (var order : QueryOrderHttpFixtures.ORDERS) {
      http.perform(get("/api/v1/orders/{id}", order.orderId())
          .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.orderId").value(order.orderId().toString()))
          .andExpect(jsonPath("$.data.accountId").value(order.accountId().toString()));
      final var executions = store.findExecutions(order.orderId().toString());
      if (order.executionId() == null) {
        assertThat(executions).isEmpty();
      } else {
        assertThat(executions).singleElement().satisfies(execution -> {
          assertThat(execution.executionId()).isEqualTo(order.executionId().toString());
          assertThat(execution.orderId()).isEqualTo(order.orderId().toString());
          assertThat(execution.accountId()).isEqualTo(order.accountId().toString());
        });
      }
    }
    assertThat(store.findOrder(QueryOrderHttpFixtures.MISSING_PARENT.toString())).isEmpty();
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.MISSING_PARENT)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
        .andExpect(status().isNotFound());
    System.out.println("QS1_03_FIXTURE_RELATIONSHIPS="
        + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "accounts", Map.of("X", QueryAccountHttpFixtures.X, "Y", QueryAccountHttpFixtures.Y),
            "orders", QueryOrderHttpFixtures.ORDERS,
            "missingParentOrderId", QueryOrderHttpFixtures.MISSING_PARENT)));
  }

  @Test
  void businessFreshnessRemainsMinimalWhenRecordedDiagnosticsAreAbsentOrHaveAGap() throws Exception {
    store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.X_EMPTY.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
    jdbc.update("DELETE FROM query_service.projection_checkpoint");
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.X_EMPTY.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("UNKNOWN"))
        .andExpect(jsonPath("$.freshness.asOf").doesNotExist());
  }

  @Test
  void concurrentOwnershipUpdateCannotMixAccountsOrSnapshotDiagnostics() throws Exception {
    final var selected = QueryOrderHttpFixtures.X_EXECUTED;
    final var newOwner = QueryOrderHttpFixtures.Y_EXECUTED.accountId();
    final CountDownLatch orderRead = new CountDownLatch(1);
    final CountDownLatch writerCommitted = new CountDownLatch(1);
    dataSource.afterOrderRead(() -> {
      orderRead.countDown();
      awaitWriter(writerCommitted);
    });

    try (var executor = Executors.newSingleThreadExecutor()) {
      final var inFlight = executor.submit(() -> http.perform(
          get("/api/v1/orders/{id}", selected.orderId())
              .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
          .andExpect(status().isOk()).andReturn());
      try {
        assertThat(orderRead.await(3, TimeUnit.SECONDS)).isTrue();
        final TransactionTemplate writer = new TransactionTemplate(transactionManager);
        writer.setTimeout(5);
        writer.executeWithoutResult(status -> {
          jdbc.update("UPDATE query_service.order_read_model SET account_id = ?, "
              + "state = 'CANCELED', leaves_quantity_shares = 0, source_offset_value = 5, "
              + "updated_at_unix_ms = 2000 WHERE order_id = ?", newOwner, selected.orderId());
          jdbc.update("UPDATE query_service.projection_checkpoint SET last_processed_offset = 5, "
              + "updated_at_unix_ms = 2000 WHERE source_topic = 'matching.events' AND partition_id = 0");
          store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
        });
      } finally {
        writerCommitted.countDown();
      }
      final var body = inFlight.get(5, TimeUnit.SECONDS).getResponse().getContentAsString();
      final var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
      assertThat(json.at("/data/orderId").asText()).isEqualTo(selected.orderId().toString());
      assertThat(json.at("/data/accountId").asText()).isEqualTo(selected.accountId().toString());
      assertThat(json.at("/data/state").asText()).isEqualTo("PARTIALLY_FILLED");
      assertThat(json.at("/freshness/projectionState").asText()).isEqualTo("NO_GAP_RECORDED");
      assertThat(body).doesNotContain(newOwner.toString());
    }
    http.perform(get("/api/v1/orders/{id}", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    http.perform(get("/api/v1/orders/{id}", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(newOwner.toString()))
        .andExpect(jsonPath("$.data.state").value("CANCELED"))
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"));
    assertThat(cache.untouched()).isTrue();
  }

  private static void awaitWriter(CountDownLatch committed) {
    try {
      if (!committed.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent test writer did not finish");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent ownership probe was interrupted", interrupted);
    }
  }
}
