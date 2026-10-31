package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesTeardownTest {
  @TempDir Path temporary;

  @Test
  void apiFailureStillAttemptsTheIndependentOwnedNodeCleanup() throws Exception {
    final var api = new IOException("API unavailable");
    final var nodes = new IOException("node cleanup failed");
    final var failure = assertThrows(IOException.class, () -> KubernetesTeardown.clean(
        () -> { throw api; }, () -> { throw nodes; }));
    assertSame(api, failure);
    assertSame(nodes, failure.getSuppressed()[0]);
  }

  @Test
  void cleanupInterruptionDoesNotPoisonTheOtherAttemptAndIsRestoredAfterward() throws Exception {
    final Path called = temporary.resolve("nodes-cleaned");
    try {
      assertThrows(InterruptedException.class, () -> KubernetesTeardown.clean(() -> {
        Thread.currentThread().interrupt();
        throw new InterruptedException("namespace wait interrupted");
      }, () -> Files.writeString(called,
          Thread.currentThread().isInterrupted() ? "poisoned" : "cleaned")));
      assertEquals("cleaned", Files.readString(called));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
