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
import com.simplematch.queryservice.store.QueryProjectionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
  @Autowired private QueryAccountCacheProbe cache;
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
}
