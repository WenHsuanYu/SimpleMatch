package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.runtime.QueryProjectionRebuildService;
import com.simplematch.queryservice.store.QueryProjectionStore;
import com.simplematch.queryservice.store.QueryProjectionSource;
import java.time.LocalDate;
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
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
@Import(QueryAccountSummaryHttpTest.DatabaseConfiguration.class)
class QueryMarketHttpTest {
  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private QueryProjectionRebuildService rebuild;
  @Autowired private QueryReadTestDataSource dataSource;
  @Autowired private QueryReadCacheProbe cache;
  @Autowired private JdbcTemplate jdbc;
  private MockMvc http;
  private String artifactId;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    QueryAccountHttpFixtures.POLICY.register(registry);
  }

  @BeforeEach
  void prepareVerifiedArtifact() throws Exception {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    dataSource.resetControls();
    cache.reset();
    store.resetForReplay();
    artifactId = QueryMarketHttpFixtures.install(rebuild).identity().value();
  }

  @AfterEach
  void clearOwnedFixtures() {
    dataSource.resetControls();
    store.resetForReplay();
  }

  @Test
  void enabledPrincipalWithoutAccountOrOperationsGrantsReadsTheVerifiedArtifact() throws Exception {
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tradingDay").value("2026-08-11"))
        .andExpect(jsonPath("$.data.artifactId").value(artifactId))
        .andExpect(jsonPath("$.data.venueMic").value("XTAI"))
        .andExpect(jsonPath("$.data.symbol").value("2330"))
        .andExpect(jsonPath("$.data.referencePriceUnits").value(1_000_000))
        .andExpect(jsonPath("$.data.routingPartition").value(0))
        .andExpect(jsonPath("$.freshness.projectionState").value("UNKNOWN"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client-a", "client-b", "client-c", "operations", "client-d"})
  void everyEnabledQueryPrincipalCanReadWithoutAdditionalPermissions(String principal) throws Exception {
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token(principal)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.artifactId").value(artifactId))
        .andExpect(jsonPath("$.data.marketRuleId").value("regular-board-common-stock"))
        .andExpect(jsonPath("$.data.lowerPriceLimitUnits").value(900_000))
        .andExpect(jsonPath("$.data.upperPriceLimitUnits").value(1_100_000))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
    assertThat(cache.untouched()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "invalid-day,XTAI,2330", "2026-02-30,XTAI,2330", "2026-8-11,XTAI,2330",
    "2026-08-11,XXXX,2330", "2026-08-11,XTA,2330", "2026-08-11,' ',2330",
    "2026-08-11,XTAI,2330-", "2026-08-11,XTAI,ABCDEFGHIJKLM", "2026-08-11,XTAI,' '"
  })
  void authenticatesBeforeRejectingInvalidSelectors(String day, String venue, String symbol)
      throws Exception {
    http.perform(get("/api/v1/market-reference/{day}/{venue}/{symbol}", day, venue, symbol))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""))
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"query\""));
    http.perform(get("/api/v1/market-reference/{day}/{venue}/{symbol}", day, venue, symbol)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isBadRequest()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void unregisteredCredentialsAndForgedIdentityHeadersCannotReadTheArtifact() throws Exception {
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + new QuerySecurityFixtures().token("client-d"))
        .header("X-Principal", QueryAccountHttpFixtures.POLICY.principalId("operations")))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""))
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"query\", error=\"invalid_token\""));
    assertThat(cache.untouched()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"xtai,2330", "' xtai ',' 2330 '"})
  void reusesArtifactInstrumentCanonicalization(String venue, String symbol) throws Exception {
    http.perform(get("/api/v1/market-reference/{day}/{venue}/{symbol}", QueryMarketHttpFixtures.DAY,
        venue, symbol).header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.venueMic").value("XTAI"))
        .andExpect(jsonPath("$.data.symbol").value("2330"));
  }

  @ParameterizedTest
  @CsvSource({"2026-08-12,XTAI,2330", "2026-08-11,ROCO,2330", "2026-08-11,XTAI,abc"})
  void validButAbsentArtifactResourcesReturnEmptyNotFound(String day, String venue, String symbol)
      throws Exception {
    final var selected = new InstrumentRef(venue, symbol);
    cache.seedLegacyMarket(LocalDate.parse(day), selected, selected);
    http.perform(get("/api/v1/market-reference/{day}/{venue}/{symbol}", day, venue, symbol)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void fullForeignOrMalformedCacheBytesCannotReplaceTheArtifactOrRevealCheckpoints() throws Exception {
    final var selected = new InstrumentRef("XTAI", "2330");
    for (int variant = 0; variant < 3; variant++) {
      if (variant == 2) {
        cache.seedMalformedMarket(QueryMarketHttpFixtures.DAY, selected);
      } else {
        cache.seedLegacyMarket(QueryMarketHttpFixtures.DAY, selected,
            variant == 0 ? selected : new InstrumentRef("ROCO", "9999"));
      }
      http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
          .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactId").value(artifactId))
          .andExpect(jsonPath("$.data.venueMic").value("XTAI"))
          .andExpect(jsonPath("$.data.referencePriceUnits").value(1_000_000))
          .andExpect(jsonPath("$.freshness.partitions").doesNotExist())
          .andExpect(content().string(Matchers.not(Matchers.containsString("legacy.cached.topic"))));
    }
    assertThat(cache.untouched()).isTrue();
    assertThat(cache.entries()).isEqualTo(1);
  }

  @Test
  void unavailablePostgresCannotUseAWarmArtifactResponse() throws Exception {
    final var selected = new InstrumentRef("XTAI", "2330");
    cache.seedLegacyMarket(QueryMarketHttpFixtures.DAY, selected, selected);
    dataSource.unavailable(true);
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    assertThat(cache.untouched()).isTrue();
  }

  @Test
  void recordedKafkaDiagnosticsDoNotChangeInstalledArtifactFactsOrProveCurrency() throws Exception {
    store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 7, 2000));
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.artifactId").value(artifactId))
        .andExpect(jsonPath("$.freshness.projectionState").value("RECOVERY_REQUIRED"))
        .andExpect(jsonPath("$.freshness.partitions").doesNotExist());
    jdbc.update("UPDATE query_service.projection_checkpoint SET recovery_state = 'READY'");
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.tradingDay").value("2026-08-11"))
        .andExpect(jsonPath("$.freshness.projectionState").value("NO_GAP_RECORDED"))
        .andExpect(jsonPath("$.freshness.asOf").doesNotExist())
        .andExpect(jsonPath("$.freshness.caughtUp").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS", "POST", "PUT", "DELETE"})
  void deniesUnapprovedMarketMethods(String method) throws Exception {
    http.perform(request(HttpMethod.valueOf(method), "/api/v1/market-reference/2026-08-11/XTAI/2330")
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/2026-08-11", "/2026-08-11/XTAI", "/2026-08-11/XTAI/2330/extra",
    "/2026-08-11/XTAI/2330/"})
  void enablingTheMarketRouteDoesNotExposeTheRouteFamily(String suffix) throws Exception {
    http.perform(get("/api/v1/market-reference" + suffix)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @CsvSource({"2026-08-12,XTAI,2330", "2026-08-11,ROCO,2330", "2026-08-11,XTAI,9999"})
  void mismatchedDurableInstrumentIdentityFailsClosed(String day, String venue, String symbol)
      throws Exception {
    dataSource.returnedMarketIdentity(LocalDate.parse(day), new InstrumentRef(venue, symbol));
    http.perform(get("/api/v1/market-reference/{day}/XTAI/2330", QueryMarketHttpFixtures.DAY)
        .header("Authorization", "Bearer " + QueryAccountHttpFixtures.POLICY.token("client-d")))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
  }
}
