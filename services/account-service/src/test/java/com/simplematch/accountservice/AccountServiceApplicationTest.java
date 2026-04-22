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

  // 驗證 account-service 啟動時能正確載入共享設定並建立執行期物件。
  // 情境：停用 gRPC 後啟動 Spring context，檢查預設環境與 gRPC 埠設定。
  @DisplayName("account-service 啟動時會載入共享設定與 runtime")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(simpleMatchConfig.getEnv()).isEqualTo("dev");
    assertThat(runtime.grpcPort()).isEqualTo(50051);
  }
}