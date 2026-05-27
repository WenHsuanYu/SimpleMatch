package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.bootstrap.AccountServiceRuntime;
import com.simplematch.config.SimpleMatchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
        "simplematch.account-service.grpc.enabled=false",
        "spring.main.web-application-type=none"
    })
class AccountServiceApplicationTest {
  @Autowired
  private SimpleMatchConfig simpleMatchConfig;

  @Autowired
  private AccountServiceRuntime runtime;

  // Verify that account-service loads shared configuration and creates its runtime on startup.
  // Scenario: start the Spring context with gRPC disabled and check the default environment and gRPC port.
  @DisplayName("account-service loads shared config and runtime on startup")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(simpleMatchConfig.getEnv()).isEqualTo("dev");
    assertThat(runtime.grpcPort()).isEqualTo(50051);
  }
}