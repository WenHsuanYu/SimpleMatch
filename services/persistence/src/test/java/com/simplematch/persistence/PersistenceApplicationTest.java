package com.simplematch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.main.web-application-type=none"})
class PersistenceApplicationTest {
  @Autowired
  private SimpleMatchConfig simpleMatchConfig;

  @DisplayName("persistence loads shared config on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(simpleMatchConfig.getEnv()).isEqualTo("dev");
  }
}