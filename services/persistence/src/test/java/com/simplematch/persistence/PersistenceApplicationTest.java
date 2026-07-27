package com.simplematch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.PlatformProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"spring.main.web-application-type=none"})
@ActiveProfiles("test")
class PersistenceApplicationTest {
  @Autowired
  private PlatformProperties platformProperties;

  @DisplayName("persistence loads shared config on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(platformProperties.environment()).isEqualTo("test");
  }
}
