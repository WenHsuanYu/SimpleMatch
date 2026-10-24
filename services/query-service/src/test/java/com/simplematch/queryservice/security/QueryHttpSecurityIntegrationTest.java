package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import com.simplematch.queryservice.store.QueryProjectionTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.web-application-type=servlet",
      "simplematch.query-service.rebuild.http-enabled=true"
    })
@ActiveProfiles("test")
@Import(QueryHttpSecurityIntegrationTest.DatabaseConfiguration.class)
class QueryHttpSecurityIntegrationTest {
  private static final QuerySecurityFixtures FIXTURES =
      new QuerySecurityFixtures()
          // Disabled D is an isolated authentication-negative variant of the approved
          // five-principal matrix.
          .property("principals[4].status", "DISABLED")
          .credential("operations-expired", "operations", true, Instant.EPOCH)
          .credential("operations-disabled", "operations", false, Instant.now().plusSeconds(3600));
  private static final String OPERATOR_TOKEN = new QuerySecurityFixtures().token("operations");

  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;
  @Autowired private FaultInjectingDataSource dataSource;
  private MockMvc http;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    FIXTURES.register(registry);
    registry.add("simplematch.query-service.rebuild.operator-token", () -> OPERATOR_TOKEN);
  }

  @BeforeEach
  void configureHttp() {
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    store.resetForReplay();
    store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 17, 1000));
  }

  @AfterEach
  void clearOwnedHttpFixture() {
    dataSource.unavailable = false;
    store.resetForReplay();
  }

  @Test
  void requiresAuthenticationBeforeReadingOperationalFreshness() throws Exception {
    http.perform(get("/api/v1/freshness"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"query\""))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
  }

  @Test
  void operationsPrincipalReadsDurableFreshnessWithoutAccountGrants() throws Exception {
    http.perform(authenticated(get("/api/v1/freshness"), "operations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partitions[0].sourceTopic").value("matching.events"))
        .andExpect(jsonPath("$.partitions[0].lastProcessedOffset").value(16))
        .andExpect(jsonPath("$.partitions[0].recoveryState").value("GAP_DETECTED"))
        .andExpect(header().string("X-SimpleMatch-Query-Policy-Version", "1"))
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andDo(
            result ->
                assertThat(
                        result
                            .getResponse()
                            .getContentAsString()
                            .contains(FIXTURES.token("operations")))
                    .isFalse());
  }

  @Test
  void accountGrantDoesNotImplyOperationsReadPermission() throws Exception {
    http.perform(authenticated(get("/api/v1/freshness"), "client-a"))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
        .andDo(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("matching.events"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"client-d", "operations-expired", "operations-disabled"})
  void inactiveIdentityAndCredentialVariantsCannotAuthenticate(String alias) throws Exception {
    http.perform(authenticated(get("/api/v1/freshness"), alias))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header().string("WWW-Authenticate", "Bearer realm=\"query\", error=\"invalid_token\""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"Bearer short", "Basic untrusted", "Bearer", "Bearer a,b", "Bearer \tinvalid"})
  void rejectsMalformedOrUnsupportedAuthenticationSchemes(String supplied) throws Exception {
    http.perform(get("/api/v1/freshness").header("Authorization", supplied))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsDuplicateAuthorizationHeaders() throws Exception {
    http.perform(
            get("/api/v1/freshness")
                .header(
                    "Authorization",
                    "Bearer " + FIXTURES.token("operations"),
                    "Bearer " + FIXTURES.token("client-a")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void forgedIdentityHeadersCannotEstablishAPrincipal() throws Exception {
    http.perform(
            get("/api/v1/freshness")
                .header("X-Principal", FIXTURES.principalId("operations"))
                .header("X-Account-Id", "untrusted-account")
                .header("X-Client-Cert", "untrusted-certificate"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCredentialCannotEstablishAQueryPrincipal() throws Exception {
    http.perform(get("/api/v1/freshness").header("Authorization", "Bearer " + OPERATOR_TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void explicitAuthenticationDoesNotCreateASessionOrAuthorizeTheNextRequest() throws Exception {
    http.perform(authenticated(get("/api/v1/freshness"), "operations"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Set-Cookie"));
    http.perform(get("/api/v1/freshness")).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/orders/not-a-uuid",
        "/api/v1/orders/not-a-uuid/executions",
        "/api/v1/market-reference/2026-10-23/XTAI/2330",
        "/api/v1/unknown",
        "/actuator/metrics",
        "/actuator/info",
        "/actuator/health",
        "/internal/query/rebuild/extra",
        "/login"
      })
  void convertedIdentityCannotEnableUnconvertedBusinessOrManagementRoutes(String path)
      throws Exception {
    http.perform(authenticated(get(path), "operations")).andExpect(status().isForbidden());
  }

  @Test
  void missingAuthenticationWinsBeforeBusinessUuidConversion() throws Exception {
    http.perform(get("/api/v1/orders/not-a-uuid")).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS", "POST", "PUT", "DELETE"})
  void deniesUnapprovedMethodsOnFreshness(String method) throws Exception {
    http.perform(
            authenticated(request(HttpMethod.valueOf(method), "/api/v1/freshness"), "operations"))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  void permitsOnlyMinimalInfrastructureHealth(String path) throws Exception {
    http.perform(get(path))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void queryCredentialCannotExecuteRebuild() throws Exception {
    http.perform(authenticated(post("/internal/query/rebuild"), "operations"))
        .andExpect(status().isUnauthorized());
    assertThat(store.freshness().partitions()).hasSize(1);
  }

  @Test
  void missingOperatorCredentialCannotExecuteRebuild() throws Exception {
    http.perform(post("/internal/query/rebuild")).andExpect(status().isUnauthorized());
    assertThat(store.freshness().partitions()).hasSize(1);
  }

  @Test
  void wrongOperatorCredentialCannotExecuteRebuild() throws Exception {
    http.perform(
            post("/internal/query/rebuild")
                .header("X-SimpleMatch-Query-Token", FIXTURES.token("client-a")))
        .andExpect(status().isUnauthorized());
    assertThat(store.freshness().partitions()).hasSize(1);
  }

  @Test
  void unregisteredCredentialAndFixSessionHeadersDoNotCreateQueryIdentity() throws Exception {
    final String foreignCredential = new QuerySecurityFixtures().token("client-a");
    http.perform(
            get("/api/v1/freshness")
                .header("Authorization", "Bearer " + foreignCredential)
                .header("X-FIX-Session", "FIX.4.4:SIMCLIENT->SIMPLEMATCH"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validOperatorOnlyRequestReachesTheRealRebuild() throws Exception {
    http.perform(
            post("/internal/query/rebuild").header("X-SimpleMatch-Query-Token", OPERATOR_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESET_COMPLETE"));
    assertThat(store.freshness().partitions()).isEmpty();
  }

  private MockHttpServletRequestBuilder authenticated(
      MockHttpServletRequestBuilder request, String alias) {
    return request.header("Authorization", "Bearer " + FIXTURES.token(alias));
  }

  @Test
  void unavailableDurableFreshnessFailsClosedWithSanitizedServiceUnavailable() throws Exception {
    dataSource.unavailable = true;
    try {
      http.perform(authenticated(get("/api/v1/freshness"), "operations"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
          .andDo(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    } finally {
      dataSource.unavailable = false;
    }
  }

  static final class FaultInjectingDataSource extends DelegatingDataSource {
    private boolean unavailable;

    FaultInjectingDataSource(DataSource delegate) {
      super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
      requireAvailable();
      return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      requireAvailable();
      return super.getConnection(username, password);
    }

    private void requireAvailable() throws SQLException {
      if (unavailable) {
        throw new SQLException("injected test database unavailable");
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseConfiguration {
    @Bean
    FaultInjectingDataSource queryHttpTestDataSource() {
      return new FaultInjectingDataSource(QueryProjectionTestDatabase.open());
    }
  }
}
