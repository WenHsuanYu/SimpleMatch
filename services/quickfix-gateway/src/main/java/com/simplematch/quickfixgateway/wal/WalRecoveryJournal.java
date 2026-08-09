package com.simplematch.quickfixgateway.wal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Append-only sidecar journal for the latest recovery state of durable WAL commands. */
public final class WalRecoveryJournal {
  private static final char FIELD_SEPARATOR = '\t';

  private final Path path;
  private final Object monitor = new Object();

  /** Initializes the recovery sidecar location next to the command WAL. */
  public WalRecoveryJournal(Path path) {
    this.path = Objects.requireNonNull(path, "path");
    try {
      final Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("failed to initialize WAL recovery journal", exception);
    }
  }

  /** Returns the conventional recovery sidecar path for a command WAL. */
  public static Path pathFor(Path walPath) {
    Objects.requireNonNull(walPath, "walPath");
    final Path fileName =
        Objects.requireNonNull(walPath.getFileName(), "WAL path must name a file");
    return walPath.resolveSibling(fileName + ".recovery");
  }

  /** Appends and forces one recovery-state transition. */
  public void appendAndFlush(String commandId, WalRecoveryState state) {
    final String normalizedCommandId = requiredField(commandId, "command_id");
    if (state == null) {
      throw new IllegalArgumentException("recovery state is required");
    }
    final byte[] bytes =
        (normalizedCommandId + FIELD_SEPARATOR + state.name() + "\n")
            .getBytes(StandardCharsets.UTF_8);
    try {
      synchronized (monitor) {
        try (FileChannel fileChannel =
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
          fileChannel.write(ByteBuffer.wrap(bytes));
          fileChannel.force(false);
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("failed to append WAL recovery state", exception);
    }
  }

  /** Reconstructs the latest durable recovery state for each command identity. */
  public Map<String, WalRecoveryState> readLatest() {
    final Map<String, WalRecoveryState> states = new LinkedHashMap<>();
    if (!Files.exists(path)) {
      return states;
    }
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber += 1;
        applyLine(states, line, lineNumber);
      }
      return states;
    } catch (IOException exception) {
      throw new IllegalStateException("failed to read WAL recovery journal", exception);
    }
  }

  /** Returns the physical recovery sidecar path. */
  public Path path() {
    return path;
  }

  private void applyLine(Map<String, WalRecoveryState> states, String line, int lineNumber) {
    if (line.isBlank()) {
      return;
    }
    final int separator = line.indexOf(FIELD_SEPARATOR);
    if (separator <= 0 || separator == line.length() - 1) {
      throw invalidLine(lineNumber);
    }
    final String commandId = requiredField(line.substring(0, separator), "command_id");
    final String rawState = line.substring(separator + 1);
    try {
      states.put(commandId, WalRecoveryState.valueOf(rawState));
    } catch (IllegalArgumentException invalidState) {
      throw invalidLine(lineNumber, invalidState);
    }
  }

  private static String requiredField(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    final boolean containsDelimiter =
        value.indexOf(FIELD_SEPARATOR) >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (containsDelimiter) {
      throw new IllegalArgumentException(fieldName + " contains an unsupported delimiter");
    }
    return value;
  }

  private static IllegalStateException invalidLine(int lineNumber) {
    return invalidLine(lineNumber, null);
  }

  private static IllegalStateException invalidLine(int lineNumber, RuntimeException cause) {
    final String message = "invalid WAL recovery journal line " + lineNumber;
    if (cause == null) {
      return new IllegalStateException(message);
    }
    return new IllegalStateException(message, cause);
  }
}
