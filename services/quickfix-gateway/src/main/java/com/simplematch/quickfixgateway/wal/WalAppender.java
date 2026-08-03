package com.simplematch.quickfixgateway.wal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
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

  /** Reads all nonblank records from the current local WAL. */
  public List<WalRecord> readAll() {
    final List<WalRecord> records = new ArrayList<>();
    if (!Files.exists(walPath)) {
      return records;
    }

    try (BufferedReader reader = Files.newBufferedReader(walPath, charset)) {
      return readRecords(reader);
    } catch (IOException exception) {
      throw new IllegalStateException("failed to replay WAL records", exception);
    }
  }

  private List<WalRecord> readRecords(BufferedReader reader) throws IOException {
    final List<WalRecord> records = new ArrayList<>();
    int lineNumber = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      lineNumber += 1;
      if (!line.isBlank()) {
        records.add(decodeLine(line, lineNumber));
      }
    }
    return records;
  }

  private WalRecord decodeLine(String line, int lineNumber) {
    try {
      return codec.decode(line);
    } catch (WalRecordCodecException exception) {
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
