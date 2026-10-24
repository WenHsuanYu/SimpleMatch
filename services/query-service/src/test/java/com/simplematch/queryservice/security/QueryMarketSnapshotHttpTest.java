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
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.sql.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Verifies snapshot association, never artifact approval currency or Kafka catch-up. */
@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
@EnabledIfSystemProperty(named = "simplematch.query.jdbc.backend", matches = "postgresql")
class QueryMarketSnapshotHttpTest {
  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionApplicationService projections;
  @Autowired private QueryProjectionRebuildService rebuild;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private QueryReadTestDataSource dataSource;
  @Autowired private QueryReadCacheProbe cache;
  @Autowired private PlatformTransactionManager transactionManager;
  private MockMvc http;
  private String artifactId;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    QueryAccountHttpFixtures.POLICY.register(registry);
  }

  @BeforeEach
  void prepareVerifiedArtifactAndRecordedDiagnostics() throws Exception {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    dataSource.resetControls();
    cache.reset();
    store.resetForReplay();
    QueryAccountHttpFixtures.projectAccounts(projections);
    artifactId = QueryMarketHttpFixtures.install(rebuild).identity().value();
  }

  @AfterEach
  void clearOwnedFixtures() {
    dataSource.resetControls();
    store.resetForReplay();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentArtifactReplacementOrDeletionCannotMixPayloadAndDiagnostics(boolean delete)
      throws Exception {
    final var replacement = QueryMarketHttpFixtures.replacement();
    assertThat(replacement.identity().value()).isNotEqualTo(artifactId);
    final CountDownLatch selected = new CountDownLatch(1);
    final CountDownLatch committed = new CountDownLatch(1);
    dataSource.afterMarketRead(() -> {
      selected.countDown();
      awaitWriter(committed);
    });
    try (var executor = Executors.newSingleThreadExecutor()) {
      final var inFlight = executor.submit(() -> http.perform(
          get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
              .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
          .andExpect(status().isOk()).andReturn());
      try {
        assertThat(selected.await(3, TimeUnit.SECONDS)).isTrue();
        final TransactionTemplate writer = new TransactionTemplate(transactionManager);
        writer.setTimeout(5);
        writer.executeWithoutResult(status -> {
          if (delete) {
            jdbc.update("DELETE FROM query_service.active_market_reference WHERE trading_day = ?",
                Date.valueOf(QueryMarketHttpFixtures.DAY));
          } else {
            rebuild.installMarketReference(replacement, 2000);
          }
          store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
        });
      } finally {
        committed.countDown();
      }
      final var response = new ObjectMapper().readTree(
          inFlight.get(5, TimeUnit.SECONDS).getResponse().getContentAsString());
      assertThat(response.at("/data/artifactId").asText()).isEqualTo(artifactId);
      assertThat(response.at("/data/referencePriceUnits").asLong()).isEqualTo(1_000_000);
      assertThat(response.at("/freshness/projectionState").asText()).isEqualTo("NO_GAP_RECORDED");
      assertThat(response.at("/freshness").size()).isEqualTo(1);
    }
    final var next = http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")));
    if (delete) {
      next.andExpect(status().isNotFound()).andExpect(content().string(""));
    } else {
      next.andExpect(status().isOk())
          .andExpect(jsonPath("$.data.artifactId").value(replacement.identity().value()))
          .andExpect(jsonPath("$.data.referencePriceUnits").value(2_000_000))
          .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"));
    }
    assertThat(cache.untouched()).isTrue();
  }

  private static void awaitWriter(CountDownLatch committed) {
    try {
      if (!committed.await(3, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent artifact writer did not finish");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent artifact probe was interrupted", interrupted);
    }
  }
}
