package com.simplematch.marketdataprojection;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifies that the non-critical projection can boot without enabling Kafka or Redis delivery. */
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "simplematch.market-data-projection.matching-events.enabled=false",
      "simplematch.market-data-projection.marketdata-events.enabled=false",
      "simplematch.market-data-projection.redis.enabled=false"
    })
@ActiveProfiles("test")
class MarketDataProjectionApplicationTest {
  @Autowired private EnvironmentProperties environmentProperties;

  @Test
  void loadsItsSharedPlatformConfiguration() {
    assertThat(environmentProperties.environment()).isEqualTo("test");
  }
}
