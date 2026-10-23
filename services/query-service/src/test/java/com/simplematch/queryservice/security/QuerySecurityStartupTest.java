package com.simplematch.queryservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class QuerySecurityStartupTest {
  @Test
  void missingPolicyPreventsTheHttpSecurityContextFromStarting() {
    new WebApplicationContextRunner()
        .withUserConfiguration(QueryHttpSecurityConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("unsupported Query security policy schema"));
  }
}
