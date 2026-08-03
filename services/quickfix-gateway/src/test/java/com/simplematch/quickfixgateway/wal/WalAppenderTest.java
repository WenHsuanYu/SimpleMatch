package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalAppenderTest {
  @TempDir Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DisplayName("semantic new-order records persist as flat v1 JSON")
  @Test
  void semanticNewOrderRecordPersistsAsFlatV1Json() throws Exception {
    final Path walPath = tempDir.resolve("semantic-new-order.wal");
    final WalRecord record =
        new WalRecord(
            new WalMetadata("v1", "cmd-1", 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "GW"),
            new WalOrderReference("O-C1", "C1", "", "ACC-1"),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "AAPL",
                    Side.SIDE_BUY,
                    "10",
                    "101.25",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("8=FIX.4.4|35=D"));

    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(record);

      final JsonNode json = objectMapper.readTree(Files.readString(walPath));
      assertThat(json.path("schemaVersion").asText()).isEqualTo("v1");
      assertThat(json.path("recordId").asText()).isEqualTo("cmd-1");
      assertThat(json.path("senderCompId").asText()).isEqualTo("CLIENT");
      assertThat(json.path("targetCompId").asText()).isEqualTo("GW");
      assertThat(json.path("messageType").asText()).isEqualTo("D");
      assertThat(json.path("orderId").asText()).isEqualTo("O-C1");
      assertThat(json.path("symbol").asText()).isEqualTo("AAPL");
      assertThat(json.path("commandType").asText()).isEqualTo("COMMAND_TYPE_NEW");
      assertThat(json.path("rawFix").asText()).isEqualTo("8=FIX.4.4|35=D");
    }
  }

  // Verify that append followed by readAll preserves both the WAL record contents and their order.
  // Scenario: write a new order and a cancel record in sequence, then verify the replay results
  // from both the file and the API.
  @DisplayName("append and readAll preserve WAL record contents")
  @Test
  void appendAndReadAllPreservesWalRecords() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord first =
          new WalRecord(
              new WalMetadata("v1", "cmd-1", 1L, "quickfix-gateway"),
              new FixSessionIdentity("CLIENT", "GW"),
              new WalOrderReference("O-C1", "C1", "", "ACC-1"),
              new WalCommand.NewOrder(
                  new WalOrderTerms(
                      "AAPL",
                      Side.SIDE_BUY,
                      "10",
                      "101.25",
                      OrderType.ORDER_TYPE_LIMIT,
                      TimeInForce.TIME_IN_FORCE_ROD)),
              new RawFixMessage("8=FIX.4.4|35=D"));
      final WalRecord second =
          new WalRecord(
              new WalMetadata("v1", "cmd-2", 2L, "quickfix-gateway"),
              new FixSessionIdentity("CLIENT", "GW"),
              new WalOrderReference("O-C1", "CXL-1", "C1", "ACC-1"),
              new WalCommand.Cancel(),
              new RawFixMessage("8=FIX.4.4|35=F"));

      walAppender.appendAndFlush(first);
      walAppender.appendAndFlush(second);

      final List<String> lines = Files.readAllLines(walPath, StandardCharsets.UTF_8);
      assertThat(lines).hasSize(2);
      final List<WalRecord> replayed = walAppender.readAll();
      assertThat(replayed).containsExactly(first, second);

      replayed.clear();
      assertThat(walAppender.readAll()).containsExactly(first, second);
    }
  }

  // Verify that the WAL contract persists each record as one JSON object per line.
  // Scenario: write a single record and read the file text directly to confirm the JSON fields and
  // values are correct.
  @DisplayName("the WAL persists as line-delimited JSON")
  @Test
  void walContractIntentionallyPersistsStructuredJsonPerLine() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord record =
          new WalRecord(
              new WalMetadata("v1", "cmd-1", 1L, "quickfix-gateway"),
              new FixSessionIdentity("CLIENT", "GW"),
              new WalOrderReference("O-C1", "C1", "", "ACC-1"),
              new WalCommand.NewOrder(
                  new WalOrderTerms(
                      "AAPL",
                      Side.SIDE_BUY,
                      "10",
                      "101.25",
                      OrderType.ORDER_TYPE_LIMIT,
                      TimeInForce.TIME_IN_FORCE_ROD)),
              new RawFixMessage("8=FIX.4.4|35=D"));

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
