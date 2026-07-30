package com.simplematch.quickfixgateway.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
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
  private final ObjectMapper objectMapper;
  private final FileChannel fileChannel;
  private final Object monitor = new Object();

  /** Opens the durable WAL at the supplied path with the supplied character encoding. */
  public WalAppender(Path walPath, Charset charset) {
    try {
      this.walPath = walPath;
      this.charset = charset;
      this.objectMapper = new ObjectMapper();
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
      final byte[] payload =
          (objectMapper.writeValueAsString(walRecord) + System.lineSeparator()).getBytes(charset);
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
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        records.add(objectMapper.readValue(line, WalRecord.class));
      }
      return records;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to replay WAL records", exception);
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
