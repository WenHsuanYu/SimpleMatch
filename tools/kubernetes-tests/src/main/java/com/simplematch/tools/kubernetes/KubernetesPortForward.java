package com.simplematch.tools.kubernetes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Owns a direct kubectl forwarder and the loopback port it actually bound. */
final class KubernetesPortForward implements AutoCloseable {
  private final Process process;
  private int port;

  private KubernetesPortForward(Process process) {
    this.process = process;
  }

  static KubernetesPortForward start(List<String> command, Path log, int remotePort)
      throws Exception {
    if (remotePort < 1 || remotePort > 65_535) {
      throw new IllegalArgumentException("remote port is out of range");
    }
    Files.createFile(log);
    final var forward = new KubernetesPortForward(new ProcessBuilder(command)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start());
    try {
      forward.awaitPort(log, remotePort);
      return forward;
    } catch (Exception failure) {
      try {
        forward.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw failure;
    }
  }

  int port() {
    return port;
  }

  private void awaitPort(Path log, int remotePort) throws Exception {
    final var ready = Pattern.compile(
        "(?m)^Forwarding from 127\\.0\\.0\\.1:([0-9]{1,5}) -> " + remotePort + "\\s*$");
    final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      final var match = ready.matcher(startupOutput(log));
      if (match.find()) {
        port = Integer.parseInt(match.group(1));
        if (port < 1 || port > 65_535) {
          throw new IOException("port-forward reported an invalid loopback port");
        }
        return;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("port-forward did not publish loopback readiness within 30 seconds");
  }

  private String startupOutput(Path log) throws IOException {
    final byte[] contents;
    try (var input = Files.newInputStream(log)) {
      contents = input.readNBytes(65_537);
    }
    if (contents.length > 65_536) {
      throw new IOException("port-forward startup output exceeded its limit");
    }
    final String output = new String(contents, StandardCharsets.UTF_8);
    if (!process.isAlive()) {
      throw new IOException("port-forward exited with code " + process.exitValue() + ": "
          + DiagnosticText.redact(output));
    }
    return output;
  }

  /** Stops only the direct kubectl process started by this owner, with bounded escalation. */
  @Override
  public void close() throws Exception {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          throw new IOException("owned port-forward process did not stop");
        }
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } finally {
        Thread.currentThread().interrupt();
      }
      throw interrupted;
    }
  }
}
