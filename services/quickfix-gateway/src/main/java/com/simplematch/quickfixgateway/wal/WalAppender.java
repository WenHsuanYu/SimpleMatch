package com.simplematch.quickfixgateway.wal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

  /** Opens the durable WAL at the supplied path with the supplied character encoding. */
  public WalAppender(Path walPath, Charset charset) {
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
      final byte[] payload = (codec.encode(walRecord) + System.lineSeparator()).getBytes(charset);
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
    final byte[] lineSeparator = System.lineSeparator().getBytes(charset);
    final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    int lineNumber = 1;
    int separatorBytesRead = 0;
    int value;
    while ((value = inputStream.read()) != -1) {
      lineBytes.write(value);
      if (value == lineSeparator[separatorBytesRead]) {
        separatorBytesRead += 1;
      } else {
        separatorBytesRead = value == lineSeparator[0] ? 1 : 0;
      }
      if (separatorBytesRead == lineSeparator.length) {
        appendLineBytes(records, lineBytes, lineSeparator.length, lineNumber);
        lineBytes.reset();
        separatorBytesRead = 0;
        lineNumber += 1;
      }
    }
    if (lineBytes.size() > 0) {
      appendLineBytes(records, lineBytes, 0, lineNumber);
    }
    return records;
  }

  private void appendLineBytes(
      List<WalRecord> records,
      ByteArrayOutputStream lineBytes,
      int separatorLength,
      int lineNumber) {
    final byte[] bytes = lineBytes.toByteArray();
    final String line = decodeLine(bytes, bytes.length - separatorLength, lineNumber);
    if (!line.isBlank()) {
      try {
        records.add(codec.decode(line));
      } catch (WalRecordCodecException exception) {
        throw new WalReplayException(lineNumber, exception);
      }
    }
  }

  private String decodeLine(byte[] bytes, int length, int lineNumber) {
    try {
      final CharsetDecoder decoder =
          charset
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
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
