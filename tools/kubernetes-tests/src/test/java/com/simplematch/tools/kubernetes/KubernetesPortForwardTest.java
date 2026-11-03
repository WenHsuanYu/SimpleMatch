package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks external-process readiness and cleanup without creating Kubernetes resources. */
class KubernetesPortForwardTest {
  @TempDir Path temporary;

  @Test
  void usesTheBoundLoopbackPortAndStopsOnlyItsForwarder() throws Exception {
    final Path pid = temporary.resolve("pid");
    try (var forward = KubernetesPortForward.start(command("ready", pid),
        temporary.resolve("forward.log"), 5001);
        var socket = new Socket("127.0.0.1", forward.port())) {
      socket.setSoTimeout(5_000);
      assertEquals(42, socket.getInputStream().read());
    }
    assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid)))
        .map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  void reportsAnEarlyExitWithoutExposingCredentialText() throws Exception {
    final var failure = assertThrows(IOException.class, () -> KubernetesPortForward.start(
        command("fail", temporary.resolve("pid")), temporary.resolve("failed.log"), 5001));
    assertFalse(failure.getMessage().contains("fixture-secret"));
    assertTrue(failure.getMessage().contains("17"));
  }

  @Test
  void aShortFaultBudgetBoundsAForwarderThatNeverBecomesReady() {
    assertTimeout(Duration.ofSeconds(3), () -> assertThrows(TimeoutException.class,
        () -> KubernetesPortForward.start(command("silent", temporary.resolve("silent-pid")),
            temporary.resolve("silent.log"), 5001, Duration.ofMillis(200))));
  }

  private static List<String> command(String mode, Path pid) throws Exception {
    final String classes = Path.of(Forwarder.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).toString();
    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", classes, Forwarder.class.getName(), mode, pid.toString());
  }

  /** Simulates only the external CLI contract while holding a real assigned port. */
  public static final class Forwarder {
    /** Publishes readiness or a terminal failure for the process adapter. */
    public static void main(String[] arguments) throws Exception {
      Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
      if ("fail".equals(arguments[0])) {
        System.err.println("cannot forward password=fixture-secret");
        System.exit(17);
      }
      try (var server = new ServerSocket(0, 1,
          java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
        if (!"silent".equals(arguments[0])) {
          System.out.println("Forwarding from 127.0.0.1:" + server.getLocalPort() + " -> 5001");
          System.out.flush();
        }
        try (var connection = server.accept()) {
          connection.getOutputStream().write(42);
          Thread.sleep(120_000);
        }
      }
    }
  }
}
