package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.queryservice.store.QueryProjectionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Tests a separately activated V2 snapshot, not deployment convergence or effective revocation. */
@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
class QueryAccountPolicySnapshotHttpTest {
  private static final QuerySecurityFixtures V2 =
      new QuerySecurityFixtures(QueryAccountHttpFixtures.POLICY)
          .property("policy-version", "2")
          .withoutProperty("principals[2].account-grants[1]")
          .property("principals[4].status", "DISABLED");

  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionApplicationService projections;
  @Autowired private QueryProjectionRebuildService rebuild;
  @Autowired private QueryReadCacheProbe cache;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc http;

  @DynamicPropertySource
  static void provisionV2Policy(DynamicPropertyRegistry registry) {
    V2.register(registry);
  }

  @BeforeEach
  void prepareOwnedFixtureAndWarmLegacyResponses() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    store.resetForReplay();
    QueryAccountHttpFixtures.projectAccounts(projections);
    cache.reset();
    cache.seedLegacyAccount(QueryAccountHttpFixtures.X, QueryAccountHttpFixtures.X);
    cache.seedLegacyAccount(QueryAccountHttpFixtures.Y, QueryAccountHttpFixtures.Y);
  }

  @AfterEach
  void clearOwnedFixture() {
    store.resetForReplay();
  }

  @Test
  void warmCacheCannotRestoreClientCsRevokedYGrantOrDisableAnotherClientsGrant() throws Exception {
    http.perform(get("/api/v1/accounts/{id}/summary", QueryAccountHttpFixtures.Y)
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isNotFound()).andExpect(content().string(""))
        .andExpect(header().string("X-SimpleMatch-Query-Policy-Version", "2"));
    http.perform(get("/api/v1/accounts/{id}/summary", QueryAccountHttpFixtures.X)
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(QueryAccountHttpFixtures.X));
    http.perform(get("/api/v1/accounts/{id}/summary", QueryAccountHttpFixtures.Y)
        .header("Authorization", "Bearer " + V2.token("client-b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(QueryAccountHttpFixtures.Y));
    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(2);
  }

  @Test
  void disabledPrincipalCannotUseItsPreviouslyValidCredentialWithWarmCache() throws Exception {
    http.perform(get("/api/v1/accounts/{id}/summary", QueryAccountHttpFixtures.X)
        .header("Authorization", "Bearer " + V2.token("client-d")))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void revokedAccountGrantCannotBeRestoredByAnOldOrderEnvelope() throws Exception {
    QueryOrderHttpFixtures.seed(jdbc);
    final var y = QueryOrderHttpFixtures.Y_EXECUTED;
    cache.seedLegacyOrder(y.orderId().toString(), y.orderId().toString(), y.accountId().toString());
    http.perform(get("/api/v1/orders/{id}", y.orderId())
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isNotFound()).andExpect(content().string(""))
        .andExpect(header().string("X-SimpleMatch-Query-Policy-Version", "2"));
    http.perform(get("/api/v1/orders/{id}", QueryOrderHttpFixtures.X_EXECUTED.orderId())
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(QueryAccountHttpFixtures.X));
    http.perform(get("/api/v1/orders/{id}", y.orderId())
        .header("Authorization", "Bearer " + V2.token("client-b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(QueryAccountHttpFixtures.Y));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void disabledIdentityCannotUseAWarmOrderResponse() throws Exception {
    QueryOrderHttpFixtures.seed(jdbc);
    final var order = QueryOrderHttpFixtures.X_EXECUTED;
    cache.seedLegacyOrder(order.orderId().toString(), order.orderId().toString(), order.accountId().toString());
    http.perform(get("/api/v1/orders/{id}", order.orderId())
        .header("Authorization", "Bearer " + V2.token("client-d")))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void warmExecutionsCannotRestoreCsRevokedYGrantOrRemoveCsXAndBsYAccess() throws Exception {
    QueryOrderHttpFixtures.seed(jdbc);
    final var x = QueryOrderHttpFixtures.X_EXECUTED;
    final var y = QueryOrderHttpFixtures.Y_EXECUTED;
    cache.seedLegacyExecutions(y.orderId().toString(), y.orderId().toString(), y.accountId().toString());
    http.perform(get("/api/v1/orders/{id}/executions", y.orderId())
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isNotFound()).andExpect(content().string(""))
        .andExpect(header().string("X-SimpleMatch-Query-Policy-Version", "2"));
    http.perform(get("/api/v1/orders/{id}/executions", x.orderId())
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderId").value(x.orderId().toString()))
        .andExpect(jsonPath("$.data[0].accountId").value(QueryAccountHttpFixtures.X));
    http.perform(get("/api/v1/orders/{id}/executions", y.orderId())
        .header("Authorization", "Bearer " + V2.token("client-b")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderId").value(y.orderId().toString()))
        .andExpect(jsonPath("$.data[0].accountId").value(QueryAccountHttpFixtures.Y));
    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(3);
  }

  @Test
  void disabledIdentityCannotUseAWarmExecutionCollection() throws Exception {
    QueryOrderHttpFixtures.seed(jdbc);
    final var parent = QueryOrderHttpFixtures.X_EXECUTED;
    cache.seedLegacyExecutions(parent.orderId().toString(), parent.orderId().toString(), parent.accountId().toString());
    http.perform(get("/api/v1/orders/{id}/executions", parent.orderId())
        .header("Authorization", "Bearer " + V2.token("client-d")))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void disabledPrincipalCannotReadMarketEvenWithAWarmResponse() throws Exception {
    QueryMarketHttpFixtures.install(rebuild);
    final var selected = new InstrumentRef("XTAI", "2330");
    cache.seedLegacyMarket(QueryMarketHttpFixtures.DAY, selected, selected);
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + V2.token("client-d")))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void removingAnAccountGrantDoesNotRemoveIndependentMarketPermission() throws Exception {
    final var artifact = QueryMarketHttpFixtures.install(rebuild);
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + V2.token("client-c")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.artifactId").value(artifact.identity().value()));
    assertThat(cache.untouched()).isTrue();
  }
}
