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
import com.simplematch.queryservice.store.QueryProjectionTestDatabase;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

@SpringBootTest(
    classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
class QueryAccountSummaryHttpTest {
  private static final QuerySecurityFixtures FIXTURES = QueryAccountHttpFixtures.POLICY;
  private static final String ACCOUNT_X = QueryAccountHttpFixtures.X;
  private static final String ACCOUNT_Y = QueryAccountHttpFixtures.Y;

  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionApplicationService projections;
  @Autowired private QueryReadTestDataSource dataSource;
  @Autowired private QueryReadCacheProbe cache;
  @Autowired private PlatformTransactionManager transactionManager;
  private MockMvc http;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    FIXTURES.register(registry);
  }

  @BeforeEach
  void prepareOwnedFixture() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    store.resetForReplay();
    cache.reset();
    QueryAccountHttpFixtures.projectAccounts(projections);
  }

  @AfterEach
  void clearOwnedFixture() {
    dataSource.resetControls();
    store.resetForReplay();
  }

  @Test
  void grantedPrincipalReadsItsAccountSummary() throws Exception {
    http.perform(
            get("/api/v1/accounts/{accountId}/summary", ACCOUNT_X)
                .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_X))
        .andExpect(jsonPath("$.data.reservedNotionalUnits").value(1000));
  }

  @Test
  void businessEnvelopeExposesOnlyTheRecordedProjectionState() throws Exception {
    http.perform(
            get("/api/v1/accounts/{accountId}/summary", ACCOUNT_X)
                .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("NO_GAP_RECORDED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist())
        .andExpect(jsonPath("$.freshness.asOf").doesNotExist());
  }

  @Test
  void absentCheckpointsReturnUnknownWithoutClaimingCatchUp() throws Exception {
    new JdbcTemplate(dataSource).update("DELETE FROM query_service.projection_checkpoint");
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("UNKNOWN"));
  }

  @Test
  void knownGapReturnsRecoveryRequiredWithoutTransportDetails() throws Exception {
    store.markRecoveryRequired(new QueryProjectionSource("account.lifecycle", 0, 4, 2000));
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
  }

  @ParameterizedTest
  @CsvSource({
    "client-a,X,200", "client-a,Y,404", "client-b,X,404", "client-b,Y,200",
    "client-c,X,200", "client-c,Y,200", "operations,X,404", "operations,Y,404",
    "client-d,X,404", "client-d,Y,404"
  })
  void enforcesTheApprovedAccountMatrix(String principal, String account, int expected)
      throws Exception {
    final String accountId = "X".equals(account) ? ACCOUNT_X : ACCOUNT_Y;
    final var result = http.perform(get("/api/v1/accounts/{id}/summary", accountId)
        .header("Authorization", "Bearer " + FIXTURES.token(principal)))
        .andExpect(status().is(expected))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    if (expected == 200) {
      result.andExpect(jsonPath("$.data.accountId").value(accountId));
    } else {
      result.andExpect(content().string(""));
    }
  }

  @Test
  void missingAndDeniedAccountsHaveTheSameConcealedResponse() throws Exception {
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_Y)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    new JdbcTemplate(dataSource).update(
        "DELETE FROM query_service.account_summary_read_model WHERE account_id = ?",
        UUID.fromString(ACCOUNT_X));
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "0198A001-0000-7000-8000-000000000003"})
  void authenticatesBeforeCanonicalAccountParsing(String supplied) throws Exception {
    http.perform(get("/api/v1/accounts/{id}/summary", supplied))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/accounts/{id}/summary", supplied)
        .header("Authorization", "Bearer " + new QuerySecurityFixtures().token("client-a")))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/accounts/{id}/summary", supplied)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isBadRequest()).andExpect(content().string(""));
  }

  @Test
  void ignoresForgedAccountAndPrincipalHeaders() throws Exception {
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_Y)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a"))
        .header("X-Principal", FIXTURES.principalId("client-b"))
        .header("X-Account-Id", ACCOUNT_X))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @Test
  void bypassesLegacyAndForeignCacheResponsesWithoutReadingWritingOrDeletingThem() throws Exception {
    cache.seedLegacyAccount(ACCOUNT_X, ACCOUNT_Y);
    cache.seedLegacyAccount(ACCOUNT_Y, ACCOUNT_X);

    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_X))
        .andExpect(jsonPath("$.data.reservedNotionalUnits").value(1000))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist())
        .andExpect(content().string(Matchers.not(Matchers.containsString(ACCOUNT_Y))));

    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(2);
  }

  @Test
  void warmCacheCannotReplaceAnUnavailableRequiredDatabaseRead() throws Exception {
    cache.seedLegacyAccount(ACCOUNT_X, ACCOUNT_X);
    dataSource.unavailable(true);

    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));

    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void deniesAnUngrantedAccountEvenWhenTheDatabaseIsUnavailable() throws Exception {
    dataSource.unavailable(true);
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_Y)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS", "POST", "PUT", "DELETE"})
  void rejectsUnapprovedMethods(String method) throws Exception {
    http.perform(request(HttpMethod.valueOf(method), "/api/v1/accounts/" + ACCOUNT_X + "/summary")
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isForbidden());
  }

  @Test
  void mismatchedDurableAccountIdentityFailsClosedWithoutForeignData() throws Exception {
    dataSource.returnedAccount(UUID.fromString(ACCOUNT_Y));
    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  @EnabledIfSystemProperty(named = "simplematch.query.jdbc.backend", matches = "postgresql")
  void concurrentWriterCannotMixOldAccountDataWithNewCheckpointState() throws Exception {
    final CountDownLatch accountRead = new CountDownLatch(1);
    final CountDownLatch writerCommitted = new CountDownLatch(1);
    dataSource.afterAccountRead(() -> {
      accountRead.countDown();
      awaitWriter(writerCommitted);
    });

    try (var executor = Executors.newSingleThreadExecutor()) {
      final var inFlight = executor.submit(() -> http.perform(
          get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
              .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
          .andExpect(status().isOk()).andReturn());
      try {
        assertThat(accountRead.await(3, TimeUnit.SECONDS)).isTrue();
        final TransactionTemplate writer = new TransactionTemplate(transactionManager);
        writer.setTimeout(5);
        writer.executeWithoutResult(status -> {
          QueryAccountHttpFixtures.projectAccount(projections, ACCOUNT_X, 3000, 2);
          store.markRecoveryRequired(new QueryProjectionSource("account.lifecycle", 0, 4, 2000));
        });
      } finally {
        writerCommitted.countDown();
      }
      final var response = inFlight.get(5, TimeUnit.SECONDS).getResponse().getContentAsString();
      final var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
      assertThat(json.at("/data/reservedNotionalUnits").asLong()).isEqualTo(1000);
      assertThat(json.at("/freshness/projectionState").asText()).isEqualTo("NO_GAP_RECORDED");
    }

    http.perform(get("/api/v1/accounts/{id}/summary", ACCOUNT_X)
        .header("Authorization", "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reservedNotionalUnits").value(3000))
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"));
  }

  private static void awaitWriter(CountDownLatch committed) {
    try {
      if (!committed.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent test writer did not finish");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent snapshot probe was interrupted", interrupted);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseConfiguration {
    @Bean
    QueryReadTestDataSource queryAccountTestDataSource() {
      return new QueryReadTestDataSource(QueryProjectionTestDatabase.open());
    }

    @Bean
    @Primary
    QueryReadCacheProbe queryAccountTestCache() {
      return new QueryReadCacheProbe();
    }
  }
}
