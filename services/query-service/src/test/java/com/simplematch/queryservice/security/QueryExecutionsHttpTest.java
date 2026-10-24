package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionStore;
import com.simplematch.queryservice.store.QueryProjectionSource;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
class QueryExecutionsHttpTest {
  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionApplicationService projections;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private QueryReadTestDataSource dataSource;
  @Autowired private QueryReadCacheProbe cache;
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
  void grantedParentDisclosesOnlyItsExecutions() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    http.perform(get("/api/v1/orders/{id}/executions", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].executionId").value(order.executionId().toString()))
        .andExpect(jsonPath("$.data[0].orderId").value(order.orderId().toString()))
        .andExpect(jsonPath("$.data[0].accountId").value(order.accountId().toString()))
        .andExpect(jsonPath("$.data[0].fillQuantityShares").value(5))
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
  void appliesGrantsBeforeReturningAnEmptyOrNonemptyCollection(String principal, String name, int expected)
      throws Exception {
    final var order = QueryOrderHttpFixtures.named(name);
    final var result = http.perform(get("/api/v1/orders/{id}/executions", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token(principal)))
        .andExpect(status().is(expected))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    if (expected == 200) {
      result.andExpect(jsonPath("$.data.length()").value(order.executionId() == null ? 0 : 1))
          .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
      if (order.executionId() != null) {
        result.andExpect(jsonPath("$.data[0].executionId").value(order.executionId().toString()))
            .andExpect(jsonPath("$.data[0].orderId").value(order.orderId().toString()))
            .andExpect(jsonPath("$.data[0].accountId").value(order.accountId().toString()));
      }
    } else {
      result.andExpect(content().string(""));
    }
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void aMissingParentCannotBeAnAuthorizedEmptyCollectionOrCachedSuccess() throws Exception {
    cache.seedLegacyExecutions(QueryOrderHttpFixtures.MISSING_PARENT.toString(),
        QueryOrderHttpFixtures.MISSING_PARENT.toString(), QueryAccountHttpFixtures.X);
    http.perform(get("/api/v1/orders/{id}/executions", QueryOrderHttpFixtures.MISSING_PARENT)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    http.perform(get("/api/v1/orders/{id}/executions", QueryOrderHttpFixtures.Y_EMPTY.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "0198A001-0000-7000-8000-000000000002"})
  void authenticatesBeforeValidatingTheParentUuid(String supplied) throws Exception {
    http.perform(get("/api/v1/orders/{id}/executions", supplied))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""))
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"query\""));
    http.perform(get("/api/v1/orders/{id}/executions", supplied)
        .header("Authorization", "Bearer " + new QuerySecurityFixtures().token("client-a")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"query\", error=\"invalid_token\""));
    http.perform(get("/api/v1/orders/{id}/executions", supplied)
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isBadRequest()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void forgedClientSelectorsCannotReplaceParentOwnership() throws Exception {
    http.perform(get("/api/v1/orders/{id}/executions", QueryOrderHttpFixtures.Y_EXECUTED.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a"))
        .header("X-Principal", QueryOrderHttpFixtures.POLICY.principalId("client-b"))
        .header("X-Account-Id", QueryAccountHttpFixtures.X)
        .queryParam("accountId", QueryAccountHttpFixtures.X))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @Test
  void ignoresStaleForeignFullEnvelopesWithoutCacheReadsFillsOrClears() throws Exception {
    final var selected = QueryOrderHttpFixtures.X_EXECUTED;
    final var foreign = QueryOrderHttpFixtures.Y_EXECUTED;
    cache.seedLegacyExecutions(selected.orderId().toString(), foreign.orderId().toString(),
        foreign.accountId().toString());
    http.perform(get("/api/v1/orders/{id}/executions", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].orderId").value(selected.orderId().toString()))
        .andExpect(jsonPath("$.data[0].accountId").value(selected.accountId().toString()))
        .andExpect(jsonPath("$.data[0].fillQuantityShares").value(5))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist())
        .andExpect(content().string(Matchers.not(Matchers.containsString(foreign.accountId().toString()))));
    cache.seedLegacyExecutions(selected.orderId().toString(), selected.orderId().toString(),
        selected.accountId().toString());
    http.perform(get("/api/v1/orders/{id}/executions", selected.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].fillQuantityShares").value(5));
    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(1);
  }

  @Test
  void unavailableOwnershipCannotFallBackToAWarmCollection() throws Exception {
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    cache.seedLegacyExecutions(order.orderId().toString(), order.orderId().toString(), order.accountId().toString());
    dataSource.unavailable(true);
    http.perform(get("/api/v1/orders/{id}/executions", order.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void mismatchedParentIdentityCannotAuthorizeItsCollection() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    dataSource.returnedOrderIdentity(QueryOrderHttpFixtures.Y_EXECUTED.orderId(), parent.accountId());
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void rejectsAnEntireMixedAccountCollectionRatherThanFilteringForeignRows() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    addExecution(parent, UUID.randomUUID(), QueryOrderHttpFixtures.Y_EXECUTED.accountId());
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void rejectsAnEntireMixedOrderReplyEvenWhenBothAccountsAreGranted() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    final UUID secondExecution = UUID.randomUUID();
    addExecution(parent, secondExecution, parent.accountId());
    dataSource.returnedExecutionIdentity(secondExecution,
        QueryOrderHttpFixtures.X_EMPTY.orderId(), parent.accountId());
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-c")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void preservesDeterministicSourceOrderingForAValidMultirowCollection() throws Exception {
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    final UUID laterExecution = UUID.randomUUID();
    addExecution(parent, laterExecution, parent.accountId());
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].executionId").value(parent.executionId().toString()))
        .andExpect(jsonPath("$.data[1].executionId").value(laterExecution.toString()))
        .andExpect(jsonPath("$.data[*].orderId").value(Matchers.everyItem(Matchers.is(parent.orderId().toString()))))
        .andExpect(jsonPath("$.data[*].accountId").value(Matchers.everyItem(Matchers.is(parent.accountId().toString()))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS", "POST", "PUT", "DELETE"})
  void deniesUnapprovedExecutionMethods(String method) throws Exception {
    http.perform(request(HttpMethod.valueOf(method),
        "/api/v1/orders/" + QueryOrderHttpFixtures.X_EMPTY.orderId() + "/executions")
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isForbidden());
  }

  @Test
  void businessDiagnosticsNeverExposeFullCheckpointsOrClaimAnAsOfTime() throws Exception {
    store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
    http.perform(get("/api/v1/orders/{id}/executions", QueryOrderHttpFixtures.X_EXECUTED.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
    jdbc.update("DELETE FROM query_service.projection_checkpoint");
    http.perform(get("/api/v1/orders/{id}/executions", QueryOrderHttpFixtures.X_EMPTY.orderId())
        .header("Authorization", "Bearer " + QueryOrderHttpFixtures.POLICY.token("client-a")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.freshness.projectionState").value("UNKNOWN"))
        .andExpect(jsonPath("$.freshness.asOf").doesNotExist());
  }

  private void addExecution(QueryOrderHttpFixtures.Order parent, UUID executionId, UUID accountId) {
    jdbc.update("INSERT INTO query_service.execution_read_model "
        + "(execution_id, order_id, account_id, venue_mic, symbol, side, fill_quantity_shares, "
        + "fill_price_units, cumulative_quantity_shares, leaves_quantity_shares, average_price_units, "
        + "source_event_id, source_partition_id, source_offset_value, executed_at_unix_ms) "
        + "SELECT ?, order_id, ?, venue_mic, symbol, side, fill_quantity_shares, "
        + "fill_price_units, cumulative_quantity_shares, leaves_quantity_shares, average_price_units, "
        + "source_event_id, source_partition_id, source_offset_value, executed_at_unix_ms + 1 "
        + "FROM query_service.execution_read_model WHERE execution_id = ?",
        executionId, accountId, parent.executionId());
  }
}
