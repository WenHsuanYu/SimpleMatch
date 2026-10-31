package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationWorkspaceTest {
  @TempDir Path temporary;

  @Test
  void rejectsAReusedEvidenceDestinationAndPreservesItsContents() throws Exception {
    final Path evidence = Files.createDirectory(temporary.resolve("existing"));
    final Path sentinel = Files.writeString(evidence.resolve("foreign.txt"), "preserve");

    assertThrows(IllegalStateException.class,
        () -> VerificationWorkspace.open(evidence, temporary.resolve("leases"), "daemon-one"));

    assertEquals("preserve", Files.readString(sentinel));
  }

  @Test
  void refusesAnEmptyExistingDestination() throws Exception {
    final Path evidence = Files.createDirectory(temporary.resolve("empty"));
    assertThrows(IllegalStateException.class,
        () -> VerificationWorkspace.open(evidence, temporary.resolve("leases"), "daemon-one"));
  }

  @Test
  void excludesConflictingRunsAndReleasesTheLeaseWithoutRemovingEvidence() throws Exception {
    final Path coordination = temporary.resolve("leases");
    final Path first = temporary.resolve("first");
    final Path second = temporary.resolve("second");
    final String firstRun;
    try (var run = VerificationWorkspace.open(first, coordination, "daemon-one")) {
      firstRun = run.runId();
      assertEquals(firstRun, Files.readString(run.evidence().resolve("run-id")));
      assertThrows(IllegalStateException.class,
          () -> VerificationWorkspace.open(second, coordination, "daemon-one"));
      assertFalse(Files.exists(second));
    }
    assertTrue(Files.isDirectory(first));
    try (var run = VerificationWorkspace.open(second, coordination, "daemon-one")) {
      assertNotEquals(firstRun, run.runId());
    }
  }

  @Test
  void refusesLinkedEvidenceWithoutChangingTheTarget() throws Exception {
    final Path target = Files.createDirectory(temporary.resolve("foreign"));
    final Path link = Files.createSymbolicLink(temporary.resolve("link"), target);
    assertThrows(IllegalArgumentException.class,
        () -> VerificationWorkspace.open(link.resolve("new"), temporary.resolve("leases"), "one"));
    assertFalse(Files.exists(target.resolve("new")));
  }

  @Test
  void excludesAnotherJvmAndReleasesTheOperatingSystemLease() throws Exception {
    final Path first = temporary.resolve("child-evidence");
    final Path coordination = temporary.resolve("leases");
    final String classpath = new File(VerificationWorkspace.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).getPath() + File.pathSeparator
        + new File(VerificationWorkspaceTest.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).getPath();
    final Process child = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
        LeaseProcess.class.getName(), first.toString(), coordination.toString())
        .redirectErrorStream(true).start();
    try {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(first.resolve("run-id")) && child.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(child.isAlive() && Files.exists(first.resolve("run-id")));
      assertThrows(IllegalStateException.class, () -> VerificationWorkspace.open(
          temporary.resolve("conflict"), coordination, "daemon-one"));
      child.getOutputStream().write(1);
      child.getOutputStream().flush();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue());
      try (var run = VerificationWorkspace.open(
          temporary.resolve("after-child"), coordination, "daemon-one")) {
        assertTrue(Files.isRegularFile(run.evidence().resolve("run-id")));
      }
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /** Separate JVM fixture that holds the public workspace until its parent acknowledges it. */
  public static final class LeaseProcess {
    private LeaseProcess() {}

    /** Opens the real lease and waits for the parent process to release the fixture. */
    public static void main(String[] args) throws Exception {
      try (var run = VerificationWorkspace.open(Path.of(args[0]), Path.of(args[1]), "daemon-one")) {
        if (System.in.read() != 1) {
          throw new IllegalStateException("parent did not acknowledge the lease fixture");
        }
      }
    }
  }
}
