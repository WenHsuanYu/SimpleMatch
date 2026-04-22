package com.simplematch.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchConfig;
import com.simplematch.riskservice.bootstrap.RiskServiceRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
    "simplematch.postgres.dsn=jdbc:h2:mem:risk-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "simplematch.risk-service.grpc.enabled=false",
        "spring.main.web-application-type=none"
    })
class RiskServiceApplicationTest {
  @Autowired
  private SimpleMatchConfig simpleMatchConfig;

  @Autowired
  private RiskServiceRuntime runtime;

  // 驗證 risk-service 啟動時能載入共享設定、覆寫資料庫 DSN，並建立 runtime。
  // 情境：停用 gRPC 後啟動 Spring context，確認預設環境、gRPC 埠與測試資料庫連線值。
  @DisplayName("risk-service 啟動時會載入共享設定與 runtime")
  @Test
  void contextLoadsWithSharedConfig() {
    assertThat(simpleMatchConfig.getEnv()).isEqualTo("dev");
    assertThat(runtime.grpcPort()).isEqualTo(50052);
    assertThat(simpleMatchConfig.getPostgres().getDsn()).isEqualTo("jdbc:h2:mem:risk-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
  }
}