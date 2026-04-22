package com.simplematch.quickfixgateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.SimpleMatchConfig;
import com.simplematch.quickfixgateway.config.QuickFixGatewayRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
    "simplematch.quickfix-gateway.acceptor-enabled=false",
  "simplematch.quickfix-gateway.data-plane-enabled=false",
  "simplematch.quickfix-gateway.replay-enabled=false",
    "spring.kafka.listener.auto-startup=false",
        "spring.main.web-application-type=none"
    })
class QuickFixGatewayApplicationTest {
  @Autowired
  private SimpleMatchConfig simpleMatchConfig;

  @Autowired
  private QuickFixGatewayRuntime runtime;

  // 驗證 quickfix-gateway 啟動時會載入 runtime 與必要的路徑設定。
  // 情境：停用 acceptor、data plane 與 replay 後啟動 Spring context，檢查設定值是否正確綁定。
  @DisplayName("quickfix-gateway 啟動時會載入 runtime 與路徑設定")
  @Test
  void contextLoadsWithQuickFixRuntime() {
    assertThat(simpleMatchConfig.getQuickfixGateway().getQuickfixConfigPath()).isEqualTo("config/fix/acceptor.cfg");
    assertThat(runtime.quickfixConfigPath().toString()).endsWith("config/fix/acceptor.cfg");
    assertThat(runtime.walPath().toString()).endsWith("data/fix/wal/inbound.wal");
  }
}