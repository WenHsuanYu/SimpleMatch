package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalAppenderTest {
  @TempDir
  Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  // 驗證 append 後再 readAll，WAL 記錄內容與順序都會被完整保留。
  // 情境：依序寫入新單與取消兩筆記錄，再從檔案與 API 兩側驗證回讀結果。
  @DisplayName("append 與 readAll 會保留 WAL 記錄內容")
  @Test
  void appendAndReadAllPreservesWalRecords() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord first = new WalRecord(
          "v1",
          "cmd-1",
          1L,
          "quickfix-gateway",
          "FIX.4.4:CLIENT->GW",
          "D",
          "O-C1",
          "C1",
          "",
          "ACC-1",
          "AAPL",
          Side.SIDE_BUY,
          "10",
          "101.25",
          OrderType.ORDER_TYPE_LIMIT,
          TimeInForce.TIME_IN_FORCE_ROD,
          CommandType.COMMAND_TYPE_NEW,
          "8=FIX.4.4|35=D");
      final WalRecord second = new WalRecord(
          "v1",
          "cmd-2",
          2L,
          "quickfix-gateway",
          "FIX.4.4:CLIENT->GW",
          "F",
          "O-C1",
          "CXL-1",
          "C1",
          "ACC-1",
          "AAPL",
          Side.SIDE_BUY,
          "10",
          "",
          OrderType.ORDER_TYPE_UNSPECIFIED,
          TimeInForce.TIME_IN_FORCE_UNSPECIFIED,
          CommandType.COMMAND_TYPE_CANCEL,
          "8=FIX.4.4|35=F");

      walAppender.appendAndFlush(first);
      walAppender.appendAndFlush(second);

      final List<String> lines = Files.readAllLines(walPath, StandardCharsets.UTF_8);
      assertThat(lines).hasSize(2);
      assertThat(walAppender.readAll()).containsExactly(first, second);
    }
  }

  // 驗證 WAL 契約會以每行一筆 JSON 的結構化格式落盤。
  // 情境：寫入單一記錄後直接讀取檔案文字，確認 JSON 欄位名稱與值都正確。
  @DisplayName("WAL 會以逐行 JSON 契約格式持久化")
  @Test
  void walContractIntentionallyPersistsStructuredJsonPerLine() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord record = new WalRecord(
          "v1",
          "cmd-1",
          1L,
          "quickfix-gateway",
          "FIX.4.4:CLIENT->GW",
          "D",
          "O-C1",
          "C1",
          "",
          "ACC-1",
          "AAPL",
          Side.SIDE_BUY,
          "10",
          "101.25",
          OrderType.ORDER_TYPE_LIMIT,
          TimeInForce.TIME_IN_FORCE_ROD,
          CommandType.COMMAND_TYPE_NEW,
          "8=FIX.4.4|35=D");

      walAppender.appendAndFlush(record);

      final List<String> lines = Files.readAllLines(walPath, StandardCharsets.UTF_8);
      assertThat(lines).hasSize(1);
      assertThat(lines.getFirst()).startsWith("{").endsWith("}");

      final JsonNode json = objectMapper.readTree(lines.getFirst());
      assertThat(json.path("schemaVersion").asText()).isEqualTo("v1");
      assertThat(json.path("recordId").asText()).isEqualTo("cmd-1");
      assertThat(json.path("sourceService").asText()).isEqualTo("quickfix-gateway");
      assertThat(json.path("messageType").asText()).isEqualTo("D");
      assertThat(json.path("orderId").asText()).isEqualTo("O-C1");
      assertThat(json.path("clientOrderId").asText()).isEqualTo("C1");
      assertThat(json.path("rawFix").asText()).isEqualTo("8=FIX.4.4|35=D");
    }
  }
}