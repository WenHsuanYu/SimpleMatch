package com.simplematch.tools.kubernetes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes explicit arguments with bounded output and cleanup of only its own process tree. */
final class BoundedCommand {
  static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
  private final Path directory;
  private final Map<String, String> environment;

  BoundedCommand(Path directory, Map<String, String> environment) {
    this.directory = directory;
    this.environment = Map.copyOf(environment);
  }

  Result run(List<String> arguments, String input, Duration timeout) throws Exception {
    if (arguments.isEmpty() || timeout.isNegative() || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
      throw new IllegalArgumentException("explicit command and finite positive timeout required");
    }
    final var builder = new ProcessBuilder(List.copyOf(arguments)).directory(directory.toFile());
    builder.environment().putAll(environment);
    try (var execution = new Execution(builder.start(), input)) {
      try {
        return execution.await(timeout);
      } catch (InterruptedException cancellation) {
        Thread.currentThread().interrupt();
        throw cancellation;
      }
    }
  }

  record Result(int exitCode, String output, String error, boolean truncated) {
    String checked() throws IOException {
      if (truncated) {
        throw new IOException("command output exceeded the bounded capture limit");
      }
      if (exitCode != 0) {
        final String safe = DiagnosticText.redact(error);
        final String excerpt = safe.length() <= 4_000 ? safe
            : safe.substring(0, 1_000) + "\n[intermediate progress omitted]\n"
                + safe.substring(safe.length() - 3_000);
        throw new IOException("command failed with exit " + exitCode + ": "
            + excerpt);
      }
      return output;
    }
  }

  private record Capture(String text, boolean truncated) {}

  private static Capture capture(InputStream stream) throws IOException {
    final var output = new ByteArrayOutputStream();
    final byte[] buffer = new byte[8_192];
    boolean truncated = false;
    try (stream) {
      for (int count = stream.read(buffer); count != -1; count = stream.read(buffer)) {
        final int retained = Math.min(count, OUTPUT_LIMIT - output.size());
        output.write(buffer, 0, retained);
        truncated |= retained != count;
      }
    }
    return new Capture(output.toString(StandardCharsets.UTF_8), truncated);
  }

  private static final class Execution implements AutoCloseable {
    private final Process process;
    private final ExecutorService pipes = Executors.newVirtualThreadPerTaskExecutor();
    private final Future<Capture> output;
    private final Future<Capture> error;
    private final Future<?> input;
    private final Map<Long, ProcessHandle> children = new LinkedHashMap<>();

    private Execution(Process process, String inputText) {
      this.process = process;
      output = pipes.submit(() -> capture(process.getInputStream()));
      error = pipes.submit(() -> capture(process.getErrorStream()));
      input = pipes.submit(() -> {
        try (var stream = process.getOutputStream()) {
          stream.write(inputText.getBytes(StandardCharsets.UTF_8));
        }
        return null;
      });
    }

    private Result await(Duration timeout) throws Exception {
      final long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        rememberChildren();
        final long remaining = remaining(deadline);
        if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
            TimeUnit.NANOSECONDS)) {
          break;
        }
      }
      input.get(remaining(deadline), TimeUnit.NANOSECONDS);
      final Capture standard = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
      final Capture diagnostic = error.get(remaining(deadline), TimeUnit.NANOSECONDS);
      return new Result(process.exitValue(), standard.text(), diagnostic.text(),
          standard.truncated() || diagnostic.truncated());
    }

    private static long remaining(long deadline) throws TimeoutException {
      final long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new TimeoutException("verification command exceeded its deadline");
      }
      return remaining;
    }

    private void rememberChildren() {
      process.descendants().forEach(child -> children.put(child.pid(), child));
    }

    @Override
    public void close() throws IOException {
      boolean interrupted = Thread.interrupted();
      try {
        terminateOwnedProcesses();
        pipes.shutdownNow();
        if (!pipes.awaitTermination(2, TimeUnit.SECONDS)) {
          throw new IOException("owned command streams did not close within the cleanup deadline");
        }
      } catch (InterruptedException cancellation) {
        interrupted = true;
        forceStop();
        throw new IOException("command cleanup was interrupted", cancellation);
      } finally {
        pipes.shutdownNow();
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void terminateOwnedProcesses() throws IOException, InterruptedException {
      rememberChildren();
      // Stop children first so a waiting parent can reap them before it exits.
      children.values().forEach(ProcessHandle::destroyForcibly);
      if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
      }
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (anyOwnedProcessAlive() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      if (anyOwnedProcessAlive()) {
        throw new IOException("owned command process did not stop within the cleanup deadline");
      }
    }

    private boolean anyOwnedProcessAlive() {
      return process.isAlive() || children.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private void forceStop() {
      children.values().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
  }
}
