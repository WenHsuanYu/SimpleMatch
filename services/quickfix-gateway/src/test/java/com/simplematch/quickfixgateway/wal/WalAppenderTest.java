package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class WalAppenderTest {
  @TempDir Path tempDir;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DisplayName("append completes short writes before forcing the full WAL record")
  @Test
  void appendCompletesShortWritesBeforeForce() throws Exception {
    final Path walPath = tempDir.resolve("short-write.wal");
    final FileChannel channel = mock(FileChannel.class);
    try (FileChannel delegate =
        FileChannel.open(
            walPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      when(channel.write(any(ByteBuffer.class)))
          .thenAnswer(invocation -> {
            final ByteBuffer buffer = invocation.getArgument(0);
            final ByteBuffer fragment = buffer.slice();
            fragment.limit(Math.min(fragment.remaining(), 7));
            final int written = delegate.write(fragment);
            buffer.position(buffer.position() + written);
            return written;
          });
      doAnswer(invocation -> {
            assertThat(Files.readString(walPath)).endsWith("\n");
            delegate.force(false);
            return null;
          })
          .when(channel)
          .force(false);

      appendUsingChannel(walPath, channel);
      verify(channel).force(false);
    }
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      assertThat(walAppender.readAll()).containsExactly(validNewOrderRecord());
    }
  }

  @DisplayName("append fails without forcing when a write makes no progress")
  @Test
  void appendRejectsWriteWithoutProgress() throws Exception {
    final FileChannel channel = mock(FileChannel.class);
    when(channel.write(any(ByteBuffer.class)))
        .thenReturn(0)
        .thenThrow(new IOException("unexpected retry after a stalled write"));

    final IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> appendUsingChannel(tempDir.resolve("stalled-write.wal"), channel));

    assertThat(failure).hasCauseInstanceOf(IOException.class);
    assertThat(failure.getCause()).hasMessage("WAL write made no progress");
    verify(channel, never()).force(false);
  }

  @DisplayName("append fails without forcing when a later write throws")
  @Test
  void appendRejectsFailureAfterPartialWrite() throws Exception {
    final FileChannel channel = mock(FileChannel.class);
    when(channel.write(any(ByteBuffer.class)))
        .thenAnswer(invocation -> {
          final ByteBuffer buffer = invocation.getArgument(0);
          buffer.position(buffer.position() + 3);
          return 3;
        })
        .thenThrow(new IOException("disk write failed"));

    final IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> appendUsingChannel(tempDir.resolve("failed-write.wal"), channel));

    assertThat(failure).hasCauseInstanceOf(IOException.class);
    assertThat(failure.getCause()).hasMessage("disk write failed");
    verify(channel, never()).force(false);
  }

  private void appendUsingChannel(Path walPath, FileChannel channel) throws Exception {
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
      channels.when(() ->
          FileChannel.open(
              walPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND))
          .thenReturn(channel);
      try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
        walAppender.appendAndFlush(validNewOrderRecord());
      }
    }
  }

  @DisplayName("replay rejects integers outside the signed 64-bit range without changing WAL bytes")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "9223372036854775808", "-9223372036854775809",
        "18446744073709551616", "-18446744073709551616"
      })
  void replayRejectsOutOfRangeIntegers(String value) throws Exception {
    final Path walPath = tempDir.resolve("out-of-range-integer.wal");
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final ObjectNode invalid = (ObjectNode) objectMapper.readTree(Files.readString(walPath));
      invalid.put("createdAtUnixMs", new BigInteger(value));
      Files.writeString(walPath, invalid + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      final byte[] originalBytes = Files.readAllBytes(walPath);

      final WalReplayException failure = assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(2);
      assertThat(failure)
          .hasMessageContaining(
              "createdAtUnixMs must be an integer JSON number in the signed 64-bit range");
      assertThat(Files.readAllBytes(walPath)).isEqualTo(originalBytes);
    }
  }

  @DisplayName("replay preserves valid timestamp limits without numeric conversion loss")
  @ParameterizedTest
  @ValueSource(longs = {0L, Long.MAX_VALUE})
  void replayPreservesValidTimestampLimits(long value) throws Exception {
    final Path walPath = tempDir.resolve("timestamp-limits.wal");
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final ObjectNode json = (ObjectNode) objectMapper.readTree(Files.readString(walPath));
      json.put("createdAtUnixMs", value);
      Files.writeString(walPath, json + "\n", StandardCharsets.UTF_8);

      assertThat(walAppender.readAll().getFirst().createdAtUnixMs()).isEqualTo(value);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1", "v3"})
  void replayRejectsUnsupportedSchemaWithoutChangingWalBytes(String schemaVersion) throws Exception {
    final Path walPath = tempDir.resolve("unsupported-version.wal");
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final ObjectNode legacy = (ObjectNode) objectMapper.readTree(Files.readString(walPath));
      legacy.put("schemaVersion", schemaVersion);
      legacy.remove("authenticatedSessionId");
      Files.writeString(walPath, legacy.toString(), StandardCharsets.UTF_8);
      final byte[] originalBytes = Files.readAllBytes(walPath);

      final WalReplayException failure = assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(failure.lineNumber()).isEqualTo(1);
      assertThat(failure).hasMessageContaining("unsupported WAL schema version: " + schemaVersion);
      assertThat(Files.readAllBytes(walPath)).isEqualTo(originalBytes);
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "FIX.4.4:OTHER->CLIENT"})
  void replayRequiresMatchingAuthenticatedSessionIdentity(String sessionId) throws Exception {
    final Path walPath = tempDir.resolve("invalid-session.wal");
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(validNewOrderRecord());
      final ObjectNode invalid = (ObjectNode) objectMapper.readTree(Files.readString(walPath));
      if (sessionId == null) {
        invalid.remove("authenticatedSessionId");
      } else {
        invalid.put("authenticatedSessionId", sessionId);
      }
      Files.writeString(walPath, invalid.toString(), StandardCharsets.UTF_8);
      final byte[] originalBytes = Files.readAllBytes(walPath);

      assertThrows(WalReplayException.class, walAppender::readAll);

      assertThat(Files.readAllBytes(walPath)).isEqualTo(originalBytes);
    }
  }

  @DisplayName("semantic new-order records persist as flat v2 JSON")
  @Test
  void semanticNewOrderRecordPersistsAsFlatV2Json() throws Exception {
    final Path walPath = tempDir.resolve("semantic-new-order.wal");
    final WalRecord record = validNewOrderRecord();

    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(record);

      final JsonNode json = objectMapper.readTree(Files.readString(walPath));
      assertThat(json.path("schemaVersion").asText()).isEqualTo("v2");
      assertThat(json.path("recordId").asText()).isEqualTo("cmd-1");
      assertThat(json.path("senderCompId").asText()).isEqualTo("CLIENT");
      assertThat(json.path("targetCompId").asText()).isEqualTo("GW");
      assertThat(json.path("authenticatedSessionId").asText()).isEqualTo("FIX.4.4:GW->CLIENT");
      assertThat(json.path("messageType").asText()).isEqualTo("D");
      assertThat(json.path("orderId").asText()).isEqualTo("O-C1");
      assertThat(json.path("symbol").asText()).isEqualTo("AAPL");
      assertThat(json.path("commandType").asText()).isEqualTo("COMMAND_TYPE_NEW");
      assertThat(json.path("rawFix").asText()).isEqualTo("8=FIX.4.4|35=D");
    }
  }

  @DisplayName("the WAL requires UTF-8 encoding")
  @Test
  void walRequiresUtf8Encoding() {
    final IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new WalAppender(tempDir.resolve("utf16.wal"), StandardCharsets.UTF_16));

    assertThat(failure).hasMessage("WAL requires UTF-8");
  }

  /**
   * Verifies that append followed by {@code readAll} preserves both WAL record contents and their
   * ordering.
   *
   * <p>Scenario: write a new-order record and a cancel record in sequence, then verify replay from
   * the file and repeated API reads returns the same ordered records.
   */
  @DisplayName("append and readAll preserve WAL record contents")
  @Test
  void appendAndReadAllPreservesWalRecords() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (final WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecord first = validNewOrderRecord();
      final WalRecord second =
          new WalRecord(
              new WalMetadata(WalMetadata.CURRENT_SCHEMA_VERSION, "cmd-2", 2L, "quickfix-gateway"),
              new FixSessionIdentity("CLIENT", "GW", "FIX.4.4:GW->CLIENT"),
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

  /**
   * Verifies that the WAL persistence contract stores exactly one structured JSON object per
   * physical line.
   *
   * <p>Scenario: write one record, read the file text directly, and confirm both the line-delimited
   * shape and the stable serialized field values.
   */
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
      assertThat(json.path("schemaVersion").asText()).isEqualTo("v2");
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
          System.lineSeparator() + "{\"schemaVersion\":\"v2\"}" + System.lineSeparator(),
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
          validJson + lineEnding + "{\"schemaVersion\":\"v2\"}" + lineEnding,
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
        new WalMetadata(WalMetadata.CURRENT_SCHEMA_VERSION, "cmd-1", 1L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT", "GW", "FIX.4.4:GW->CLIENT"),
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
