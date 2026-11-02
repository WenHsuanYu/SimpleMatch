package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "simplematch.postgres.dsn=postgresql://configuration.invalid:5432/account",
      "simplematch.account-service.grpc.enabled=false",
      "spring.flyway.enabled=false",
      "spring.main.web-application-type=none"
    })
@ActiveProfiles("local")
class AccountServiceLocalProfileApplicationTest {
  @Autowired private EnvironmentProperties environmentProperties;

  @Test
  void startsWithTheLocalProfile() {
    assertThat(environmentProperties.environment()).isEqualTo("local");
  }
}
