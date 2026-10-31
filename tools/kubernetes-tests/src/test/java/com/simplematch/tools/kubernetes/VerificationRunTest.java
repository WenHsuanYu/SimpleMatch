package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationRunTest {
  @TempDir Path temporary;

  @Test
  void partialSetupFailurePreservesThePrimaryErrorAndUnrelatedResources() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final IOException primary = new IOException("partial setup failure");
    resources.setupFailure = primary;
    final Path evidence = temporary.resolve("evidence");

    final Exception failure = assertThrows(IOException.class, () -> VerificationRun.verify(
        new VerificationRequest(evidence, temporary.resolve("leases"), false), resources,
        run -> { throw new AssertionError("setup did not complete"); }));

    assertSame(primary, failure);
    assertEquals("foreign owner", Files.readString(resources.external.resolve("foreign")));
    assertFalse(Files.exists(resources.owned));
    assertEquals("owned resource", Files.readString(evidence.resolve("diagnostic.txt")));
  }

  @Test
  void reportsSuccessOnlyAfterAssertionsDiagnosticsAndOwnedCleanup() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final Path evidence = temporary.resolve("success");
    VerificationRun.verify(new VerificationRequest(evidence, temporary.resolve("leases"), false),
        resources, run -> assertEquals("owned resource",
            Files.readString(resources.owned.resolve("state"))));

    final JsonNode report = new ObjectMapper().readTree(evidence.resolve("summary.json").toFile());
    assertEquals("PASS", report.path("status").asText());
    assertEquals("PASS", report.path("cleanup").asText());
    assertFalse(Files.exists(resources.owned));
    assertEquals("foreign owner", Files.readString(resources.external.resolve("foreign")));
  }

  @Test
  void keepsTheBusinessFailureAndItsCleanupFailure() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final IOException primary = new IOException("business assertion failed password=primary-secret");
    final IOException cleanup = new IOException("owned cleanup failed password=cleanup-secret");
    resources.cleanupFailure = cleanup;
    final Path evidence = temporary.resolve("failed");
    final var failure = assertThrows(IOException.class, () -> VerificationRun.verify(
        new VerificationRequest(evidence, temporary.resolve("leases"), false), resources,
        run -> { throw primary; }));

    assertSame(primary, failure);
    assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
    final JsonNode report = new ObjectMapper().readTree(evidence.resolve("summary.json").toFile());
    assertEquals("FAIL", report.path("status").asText());
    assertEquals("FAIL", report.path("cleanup").asText());
    assertTrue(report.path("primary_failure").asText().contains("business assertion failed"));
    assertTrue(report.path("cleanup_failure").asText().contains("owned cleanup failed"));
    assertFalse(report.toString().contains("primary-secret"));
    assertFalse(report.toString().contains("cleanup-secret"));
    assertTrue(Files.exists(resources.owned));
  }

  @Test
  void diagnosticFailureKeepsItsCauseAndStillCleansTheOwnedResource() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final Path evidence = temporary.resolve("missing-diagnostic-source");
    assertThrows(IOException.class, () -> VerificationRun.verify(
        new VerificationRequest(evidence, temporary.resolve("leases"), false), resources,
        run -> Files.createDirectory(run.evidence().resolve("diagnostic.txt"))));
    final var report = new ObjectMapper().readTree(evidence.resolve("summary.json").toFile());
    assertEquals("FAIL", report.path("status").asText());
    assertEquals("FAIL", report.path("diagnostics").asText());
    assertEquals("PASS", report.path("cleanup").asText());
    assertTrue(report.path("diagnostic_failure").asText().contains("diagnostic.txt"));
    assertFalse(Files.exists(resources.owned));
  }

  @Test
  void cleanupFailureCannotTurnPassedAssertionsIntoSuccess() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    resources.cleanupFailure = new IOException("owned cleanup failed");
    final Path evidence = temporary.resolve("cleanup-failed");
    assertThrows(IOException.class, () -> VerificationRun.verify(
        new VerificationRequest(evidence, temporary.resolve("leases"), false), resources,
        run -> assertTrue(Files.exists(resources.owned))));

    final JsonNode report = new ObjectMapper().readTree(evidence.resolve("summary.json").toFile());
    assertEquals("PASS", report.path("assertions").asText());
    assertEquals("FAIL", report.path("status").asText());
  }

  @Test
  void explicitRetentionPreservesOwnedResourcesAndReportsRetention() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final Path evidence = temporary.resolve("retained");
    VerificationRun.verify(new VerificationRequest(evidence, temporary.resolve("leases"), true),
        resources, run -> assertTrue(Files.exists(resources.owned)));

    final JsonNode report = new ObjectMapper().readTree(evidence.resolve("summary.json").toFile());
    assertEquals("RETAINED", report.path("status").asText());
    assertEquals("RETAINED", report.path("cleanup").asText());
    assertTrue(Files.exists(resources.owned));
    assertEquals("owned resource", Files.readString(evidence.resolve("diagnostic.txt")));
  }

  @Test
  void timeoutPreservesItsIdentityAndStillCleansOwnedResources() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final TimeoutException timeout = new TimeoutException("scenario deadline");
    final var failure = assertThrows(TimeoutException.class, () -> VerificationRun.verify(
        new VerificationRequest(temporary.resolve("timeout"), temporary.resolve("leases"), false),
        resources, run -> { throw timeout; }));
    assertSame(timeout, failure);
    assertFalse(Files.exists(resources.owned));
  }

  @Test
  void recoverableCancellationAllowsCleanupAndRestoresTheInterruptFlag() throws Exception {
    final var resources = new ResourceFixture(temporary.resolve("external"));
    final var started = new CountDownLatch(1);
    final var failure = new AtomicReference<Exception>();
    final var interrupted = new AtomicBoolean();
    final Thread worker = new Thread(() -> {
      try {
        VerificationRun.verify(new VerificationRequest(temporary.resolve("cancelled"),
            temporary.resolve("leases"), false), resources, run -> {
              started.countDown();
              Thread.sleep(30_000);
            });
      } catch (Exception cancelled) {
        failure.set(cancelled);
        interrupted.set(Thread.currentThread().isInterrupted());
      }
    });
    worker.start();
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      worker.interrupt();
      worker.join(5_000);
      assertFalse(worker.isAlive());
      assertTrue(failure.get() instanceof InterruptedException);
      assertTrue(interrupted.get());
      assertFalse(Files.exists(resources.owned));
    } finally {
      worker.interrupt();
      worker.join(5_000);
    }
  }

  private static final class ResourceFixture implements VerificationResources {
    private final Path external;
    private Path owned;
    private IOException setupFailure;
    private IOException cleanupFailure;

    ResourceFixture(Path external) throws IOException {
      this.external = Files.createDirectory(external);
      Files.writeString(external.resolve("foreign"), "foreign owner");
    }

    @Override
    public String preflight() {
      return "observed-fixture-daemon";
    }

    @Override
    public void create(VerificationContext run) throws IOException {
      owned = Files.createDirectory(external.resolve(run.runId()));
      Files.writeString(owned.resolve("owner"), run.runId());
      Files.writeString(owned.resolve("state"), "owned resource");
      if (setupFailure != null) {
        throw setupFailure;
      }
    }

    @Override
    public void diagnose(VerificationContext run) throws IOException {
      Files.writeString(run.evidence().resolve("diagnostic.txt"),
          Files.readString(owned.resolve("state")));
    }

    @Override
    public void cleanup(VerificationContext run) throws IOException {
      if (Thread.currentThread().isInterrupted()) {
        throw new IOException("cleanup inherited an interrupted operation");
      }
      if (cleanupFailure != null) {
        throw cleanupFailure;
      }
      if (!run.runId().equals(Files.readString(owned.resolve("owner")))) {
        throw new IOException("resource ownership changed");
      }
      Files.delete(owned.resolve("state"));
      Files.delete(owned.resolve("owner"));
      Files.delete(owned);
    }
  }
}
