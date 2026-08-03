package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    final WalRecord record = validNewOrderRecord();

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

  @DisplayName("the v1 WAL requires UTF-8 encoding")
  @Test
  void walRequiresUtf8Encoding() {
    final IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new WalAppender(tempDir.resolve("utf16.wal"), StandardCharsets.UTF_16));

    assertThat(failure).hasMessage("WAL v1 requires UTF-8");
  }

  // Verify that append followed by readAll preserves both the WAL record contents and their order.
  // Scenario: write a new order and a cancel record in sequence, then verify the replay results
  // from both the file and the API.
  @DisplayName("append and readAll preserve WAL record contents")
  @Test
  void appendAndReadAllPreservesWalRecords() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord first = validNewOrderRecord();
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
      final WalRecord record = validNewOrderRecord();

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

  @DisplayName("replay fails at the invalid physical line and preserves WAL bytes")
  @Test
  void replayFailsAtInvalidPhysicalLineAndPreservesWalBytes() throws Exception {
    final Path walPath = tempDir.resolve("invalid-replay.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      Files.writeString(
          walPath,
          System.lineSeparator() + "{\"schemaVersion\":\"v1\"}" + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(3);
      assertThat(failure).hasMessageContaining("line 3");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay counts CR and CRLF physical lines")
  @Test
  void replayCountsAlternativePhysicalLines() throws Exception {
    assertPhysicalLineNumberForLineEnding("\r", "cr-only");
    assertPhysicalLineNumberForLineEnding("\r\n", "crlf");
  }

  private void assertPhysicalLineNumberForLineEnding(String lineEnding, String fileName)
      throws Exception {
    final Path walPath = tempDir.resolve(fileName + "-replay.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String validJson = Files.readString(walPath).trim();
      Files.writeString(
          walPath,
          validJson + lineEnding + "{\"schemaVersion\":\"v1\"}" + lineEnding,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("line 2");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay rejects a contradictory FIX and command type")
  @Test
  void replayRejectsContradictoryFixAndCommandType() throws Exception {
    final Path walPath = tempDir.resolve("contradictory-command.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String invalidLine =
          Files.readString(walPath).replace("\"messageType\":\"D\"", "\"messageType\":\"F\"");
      Files.writeString(
          walPath,
          invalidLine,
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("message_type and command_type");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay rejects trailing JSON tokens")
  @Test
  void replayRejectsTrailingJsonTokens() throws Exception {
    final Path walPath = tempDir.resolve("trailing-json.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String invalidLine = Files.readString(walPath).trim() + " {}";
      Files.writeString(
          walPath,
          invalidLine,
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("line 2");
    }
  }

  @DisplayName("replay rejects duplicate JSON fields")
  @Test
  void replayRejectsDuplicateJsonFields() throws Exception {
    final Path walPath = tempDir.resolve("duplicate-json-field.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String validJson = Files.readString(walPath).trim();
      final String invalidLine =
          validJson.substring(0, validJson.length() - 1)
              + ",\"commandType\":\"COMMAND_TYPE_NEW\"}";
      Files.writeString(
          walPath,
          invalidLine,
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay reports the line containing malformed UTF-8")
  @Test
  void replayReportsMalformedUtf8Line() throws Exception {
    final Path walPath = tempDir.resolve("malformed-utf8.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      Files.write(
          walPath,
          new byte[] {(byte) 0xC3, (byte) 0x28, (byte) '\n'},
          StandardOpenOption.APPEND);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("line 2");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay rejects malformed UTF-8 inside a JSON string")
  @Test
  void replayRejectsMalformedUtf8InsideJsonString() throws Exception {
    final Path walPath = tempDir.resolve("malformed-utf8-string.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String validJson = Files.readString(walPath).trim();
      final String rawFix = "8=FIX.4.4|35=D";
      final int rawFixStart = validJson.indexOf(rawFix);
      final String prefix = validJson.substring(0, rawFixStart + "8=FIX.4.4".length());
      final String suffix =
          validJson.substring(rawFixStart + "8=FIX.4.4".length())
              + System.lineSeparator();
      final byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
      final byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
      final ByteBuffer malformedLine =
          ByteBuffer.allocate(prefixBytes.length + 2 + suffixBytes.length);
      malformedLine.put(prefixBytes).put((byte) 0xC3).put((byte) 0x28).put(suffixBytes);
      Files.write(walPath, malformedLine.array(), StandardOpenOption.APPEND);
      final byte[] bytesBeforeReplay = Files.readAllBytes(walPath);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("line 2");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(bytesBeforeReplay);
    }
  }

  @DisplayName("replay rejects an incomplete new-order payload")
  @Test
  void replayRejectsIncompleteNewOrderPayload() throws Exception {
    final Path walPath = tempDir.resolve("incomplete-new-order.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final String invalidLine =
          Files.readString(walPath).replace("\"symbol\":\"AAPL\"", "\"symbol\":\"\"");
      Files.writeString(
          walPath,
          invalidLine,
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND);

      final WalReplayException failure =
          assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure).hasMessageContaining("symbol must be");
    }
  }

  private WalRecord validNewOrderRecord() {
    return new WalRecord(
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
  }
}
