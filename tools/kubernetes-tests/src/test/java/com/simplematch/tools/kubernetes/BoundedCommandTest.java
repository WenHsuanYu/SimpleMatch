package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedCommandTest {
  @TempDir Path temporary;

  @Test
  void timeoutStopsTheOwnedCommandAndItsChild() throws Exception {
    final Path identities = temporary.resolve("owned-pids");
    final var command = new BoundedCommand(temporary, Map.of());
    assertThrows(TimeoutException.class, () -> command.run(
        fixture("wait", identities.toString()), "", Duration.ofSeconds(2)));
    assertTrue(Files.isRegularFile(identities));
    for (String pid : Files.readAllLines(identities)) {
      assertFalse(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  @Test
  void capturesActualInputOutputAndExitStatusWithRedactedFailureDiagnostics() throws Exception {
    final var command = new BoundedCommand(temporary, Map.of());
    final var copied = command.run(fixture("copy", ""), "public payload", Duration.ofSeconds(5));
    assertEquals("public payload", copied.checked());
    final var rejected = command.run(fixture("reject", ""), "", Duration.ofSeconds(5));
    assertEquals(17, rejected.exitCode());
    final String message = assertThrows(IOException.class, rejected::checked).getMessage();
    assertFalse(message.contains("s3cr3t"));
    assertFalse(message.contains("other-secret"));
    assertFalse(message.contains("sample-bearer"));
    assertTrue(message.contains("<REDACTED>"));
  }

  @Test
  void longFailureKeepsTheActualTerminalCauseAndRedactsBothEnds() {
    final var failure = new BoundedCommand.Result(1, "",
        "password=head-secret " + "image progress\n".repeat(1_000)
            + "terminal import cause password=tail-secret", false);
    final String message = assertThrows(IOException.class, failure::checked).getMessage();
    assertTrue(message.contains("terminal import cause"));
    assertFalse(message.contains("head-secret"));
    assertFalse(message.contains("tail-secret"));
    assertTrue(message.length() < 4_200);
  }

  @Test
  void capsOutputAndRejectsAResultWhoseEvidenceWasTruncated() throws Exception {
    final var command = new BoundedCommand(temporary, Map.of());
    final var result = command.run(fixture("flood", ""), "", Duration.ofSeconds(5));
    assertEquals(0, result.exitCode());
    assertEquals(BoundedCommand.OUTPUT_LIMIT, result.output().length());
    assertTrue(result.truncated());
    assertThrows(IOException.class, result::checked);
  }

  @Test
  void cancellationCleansTheOwnedProcessTreeAndRestoresTheInterruptFlag() throws Exception {
    final Path identities = temporary.resolve("cancelled-pids");
    final var command = new BoundedCommand(temporary, Map.of());
    final var failure = new AtomicReference<Exception>();
    final var interrupted = new AtomicBoolean();
    final Thread worker = new Thread(() -> {
      try {
        command.run(fixture("wait", identities.toString()), "", Duration.ofSeconds(20));
      } catch (Exception cancelled) {
        failure.set(cancelled);
        interrupted.set(Thread.currentThread().isInterrupted());
      }
    });
    worker.setDaemon(true);
    worker.start();
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(identities) && worker.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(Files.isRegularFile(identities));
      worker.interrupt();
      worker.join(5_000);
      assertFalse(worker.isAlive());
      assertTrue(failure.get() instanceof InterruptedException);
      assertTrue(interrupted.get());
      for (String pid : Files.readAllLines(identities)) {
        assertFalse(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false));
      }
    } finally {
      worker.interrupt();
      worker.join(5_000);
    }
  }

  private List<String> fixture(String mode, String value) throws Exception {
    final String classes = new File(CommandProcess.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).getPath();
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", classes, CommandProcess.class.getName(), mode, value);
  }

  /** Real child JVM used to exercise the external process boundary. */
  public static final class CommandProcess {
    private CommandProcess() {}

    /** Publishes exact owned PIDs before waiting for the command deadline. */
    public static void main(String[] args) throws Exception {
      switch (args[0]) {
        case "copy" -> System.out.write(System.in.readAllBytes());
        case "reject" -> {
          System.err.println("password=s3cr3t postgresql://owner:other-secret@localhost/db "
              + "Authorization: Bearer sample-bearer");
          System.exit(17);
        }
        case "flood" -> System.out.print("x".repeat(2 * 1024 * 1024 + 100));
        case "wait" -> {
          final Process child = new ProcessBuilder("sleep", "30").start();
          Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\n" + child.pid());
          child.waitFor();
        }
        default -> throw new IllegalArgumentException("unknown process fixture");
      }
    }
  }
}
