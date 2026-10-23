package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simplematch.queryservice.QueryServiceApplication;
import com.simplematch.queryservice.store.QueryProjectionSource;
import com.simplematch.queryservice.store.QueryProjectionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    classes = QueryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.main.web-application-type=servlet",
      "simplematch.query-service.rebuild.http-enabled=false"
    })
@ActiveProfiles("test")
@Import(QueryHttpSecurityIntegrationTest.DatabaseConfiguration.class)
class QueryRebuildDisabledHttpTest {
  private static final QuerySecurityFixtures FIXTURES = new QuerySecurityFixtures();
  @Autowired private WebApplicationContext context;
  @Autowired private QueryProjectionStore store;

  @DynamicPropertySource
  static void provisionPolicy(DynamicPropertyRegistry registry) {
    FIXTURES.register(registry);
  }

  @AfterEach
  void clearOwnedHttpFixture() {
    store.resetForReplay();
  }

  @Test
  void disabledRebuildCannotBeActivatedByAnyOperatorHeader() throws Exception {
    store.resetForReplay();
    store.markRecoveryRequired(new QueryProjectionSource("matching.events", 0, 17, 1000));
    MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build()
        .perform(
            post("/internal/query/rebuild")
                .header("X-SimpleMatch-Query-Token", FIXTURES.token("operations")))
        .andExpect(status().isNotFound());
    assertThat(store.freshness().partitions()).hasSize(1);
  }
}
