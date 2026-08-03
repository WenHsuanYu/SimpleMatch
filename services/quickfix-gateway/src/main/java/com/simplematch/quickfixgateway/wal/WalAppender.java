package com.simplematch.quickfixgateway.wal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Appends and replays the gateway-local durable inbound FIX write-ahead log. */
public final class WalAppender implements AutoCloseable {
  private final Path walPath;
  private final Charset charset;
  private final WalRecordJsonCodec codec;
  private final FileChannel fileChannel;
  private final Object monitor = new Object();

  /** Opens the durable UTF-8 WAL at the supplied path. */
  public WalAppender(Path walPath, Charset charset) {
    if (!StandardCharsets.UTF_8.equals(charset)) {
      throw new IllegalArgumentException("WAL v1 requires UTF-8");
    }
    try {
      this.walPath = walPath;
      this.charset = charset;
      this.codec = new WalRecordJsonCodec(new com.fasterxml.jackson.databind.ObjectMapper());
      final Path parent = walPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      this.fileChannel =
          FileChannel.open(
              walPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new IllegalStateException("failed to initialize WAL appender", exception);
    }
  }

  /** Appends a record and forces its bytes to the operating-system file channel. */
  public void appendAndFlush(WalRecord walRecord) {
    try {
      final byte[] payload = (codec.encode(walRecord) + "\n").getBytes(charset);
      synchronized (monitor) {
        fileChannel.write(ByteBuffer.wrap(payload));
        fileChannel.force(false);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("failed to append WAL record", exception);
    }
  }

  /**
   * Reads all nonblank records from the current local WAL.
   *
   * @return the decoded records in physical WAL order
   * @throws WalReplayException when a nonblank line or its encoding is invalid
   */
  public List<WalRecord> readAll() {
    final List<WalRecord> records = new ArrayList<>();
    if (!Files.exists(walPath)) {
      return records;
    }

    try (InputStream inputStream = Files.newInputStream(walPath)) {
      return readRecords(inputStream);
    } catch (IOException exception) {
      throw new IllegalStateException("failed to replay WAL records", exception);
    }
  }

  private List<WalRecord> readRecords(InputStream inputStream) throws IOException {
    final List<WalRecord> records = new ArrayList<>();
    final byte[] content = inputStream.readAllBytes();
    final byte[] lineFeed = "\n".getBytes(charset);
    final byte[] carriageReturn = "\r".getBytes(charset);
    int lineStart = 0;
    int lineNumber = 1;
    while (lineStart < content.length) {
      final int terminatorStart =
          findNextLineTerminator(content, lineStart, lineFeed, carriageReturn);
      if (terminatorStart < 0) {
        appendLineBytes(records, content, lineStart, content.length - lineStart, lineNumber);
        return records;
      }
      appendLineBytes(records, content, lineStart, terminatorStart - lineStart, lineNumber);
      lineStart =
          terminatorStart
              + lineTerminatorLength(content, terminatorStart, lineFeed, carriageReturn);
      lineNumber += 1;
    }
    return records;
  }

  private int findNextLineTerminator(
      byte[] content, int start, byte[] lineFeed, byte[] carriageReturn) {
    for (int index = start; index < content.length; index += 1) {
      if (matches(content, index, lineFeed) || matches(content, index, carriageReturn)) {
        return index;
      }
    }
    return -1;
  }

  private int lineTerminatorLength(
      byte[] content, int start, byte[] lineFeed, byte[] carriageReturn) {
    if (matches(content, start, carriageReturn)) {
      final int carriageReturnEnd = start + carriageReturn.length;
      return matches(content, carriageReturnEnd, lineFeed)
          ? carriageReturn.length + lineFeed.length
          : carriageReturn.length;
    }
    return lineFeed.length;
  }

  private boolean matches(byte[] content, int start, byte[] candidate) {
    if (start + candidate.length > content.length) {
      return false;
    }
    for (int offset = 0; offset < candidate.length; offset += 1) {
      if (content[start + offset] != candidate[offset]) {
        return false;
      }
    }
    return true;
  }

  private void appendLineBytes(
      List<WalRecord> records, byte[] content, int start, int length, int lineNumber) {
    if (length == 0) {
      return;
    }
    final String line = decodeLine(content, start, length, lineNumber);
    if (!line.isBlank()) {
      try {
        records.add(codec.decode(line));
      } catch (WalRecordCodecException exception) {
        throw new WalReplayException(lineNumber, exception);
      }
    }
  }

  private String decodeLine(byte[] content, int start, int length, int lineNumber) {
    try {
      final CharsetDecoder decoder =
          charset
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder.decode(ByteBuffer.wrap(content, start, length)).toString();
    } catch (CharacterCodingException exception) {
      throw new WalReplayException(lineNumber, exception);
    }
  }

  /** Returns the physical path of the local WAL file. */
  public Path walPath() {
    return walPath;
  }

  @Override
  public void close() throws IOException {
    fileChannel.close();
  }
}
