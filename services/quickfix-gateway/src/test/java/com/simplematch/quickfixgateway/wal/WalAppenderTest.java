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

  // Verify that append followed by readAll preserves both the WAL record contents and their order.
  // Scenario: write a new order and a cancel record in sequence, then verify the replay results from both the file and the API.
  @DisplayName("append and readAll preserve WAL record contents")
  @Test
  void appendAndReadAllPreservesWalRecords() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord first = new WalRecord(
          "v1",
          "cmd-1",
          1L,
          "quickfix-gateway",
          "CLIENT",
          "GW",
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
          "CLIENT",
          "GW",
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

  // Verify that the WAL contract persists each record as one JSON object per line.
  // Scenario: write a single record and read the file text directly to confirm the JSON fields and values are correct.
  @DisplayName("the WAL persists as line-delimited JSON")
  @Test
  void walContractIntentionallyPersistsStructuredJsonPerLine() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord record = new WalRecord(
          "v1",
          "cmd-1",
          1L,
          "quickfix-gateway",
          "CLIENT",
          "GW",
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
      assertThat(json.path("senderCompId").asText()).isEqualTo("CLIENT");
      assertThat(json.path("targetCompId").asText()).isEqualTo("GW");
      assertThat(json.path("messageType").asText()).isEqualTo("D");
      assertThat(json.path("orderId").asText()).isEqualTo("O-C1");
      assertThat(json.path("clOrdId").asText()).isEqualTo("C1");
      assertThat(json.path("rawFix").asText()).isEqualTo("8=FIX.4.4|35=D");
    }
  }
}