package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Owns setup, Jupiter assertions, diagnostics, retention and exact cleanup for one live run. */
public final class VerificationRun {
  private VerificationRun() {}

  /** Executes a new scenario while preserving primary and cleanup failures. */
  public static void verify(VerificationRequest request, VerificationResources resources,
      Scenario scenario) throws Exception {
    final var workspace = VerificationWorkspace.open(
        request.evidence(), request.coordination(), resources.preflight());
    try (var scope = new Scope(workspace, resources, request.retain())) {
      try {
        resources.create(scope.context);
        scenario.verify(scope.context);
        scope.assertionsPassed = true;
      } catch (Exception | AssertionError failure) {
        scope.primaryFailure = failure;
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw failure;
      }
    }
  }

  /** Represents the actual service assertions executed by a Jupiter test. */
  @FunctionalInterface
  public interface Scenario {
    /** Performs the selected assertion against the prepared live resources. */
    void verify(VerificationContext run) throws Exception;
  }

  private static final class Scope implements AutoCloseable {
    private final VerificationWorkspace workspace;
    private final VerificationResources resources;
    private final VerificationContext context;
    private final boolean retain;
    private boolean assertionsPassed;
    private boolean cleanupInterrupted;
    private Throwable primaryFailure;
    private Exception diagnosticFailure;
    private Exception cleanupFailure;

    private Scope(
        VerificationWorkspace workspace, VerificationResources resources, boolean retain) {
      this.workspace = workspace;
      this.resources = resources;
      this.context = new VerificationContext(workspace.runId(), workspace.evidence());
      this.retain = retain;
    }

    @Override
    public void close() throws Exception {
      final boolean interrupted = Thread.interrupted();
      try {
        finish();
      } finally {
        if (interrupted || cleanupInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void finish() throws Exception {
      Exception failure = null;
      String diagnostics = "PASS";
      String cleanup = retain ? "RETAINED" : "PASS";
      try {
        resources.diagnose(context);
      } catch (Exception diagnosticError) {
        diagnosticFailure = diagnosticError;
        diagnostics = "FAIL";
        failure = recordFailure(null, diagnosticError);
      }
      if (!retain) {
        try {
          resources.cleanup(context);
        } catch (Exception cleanupError) {
          cleanupFailure = cleanupError;
          cleanup = "FAIL";
          failure = recordFailure(failure, cleanupError);
        }
      }
      try {
        workspace.close();
      } catch (Exception leaseFailure) {
        cleanup = "FAIL";
        failure = recordFailure(failure, leaseFailure);
      }
      try {
        writeReport(failure, diagnostics, cleanup);
      } catch (Exception reportFailure) {
        failure = recordFailure(failure, reportFailure);
      }
      if (failure != null) {
        throw failure;
      }
    }

    private void writeReport(Exception failure, String diagnostics, String cleanup)
        throws IOException {
      final String status = status(failure);
      final var report = Map.of(
          "schema_version", 1, "run_id", context.runId(), "status", status,
          "assertions", assertionsPassed ? "PASS" : "FAIL",
          "diagnostics", diagnostics, "cleanup", cleanup,
          "primary_failure", DiagnosticText.failure(primaryFailure),
          "diagnostic_failure", DiagnosticText.failure(diagnosticFailure),
          "cleanup_failure", DiagnosticText.failure(cleanupFailure));
      Files.write(context.evidence().resolve("summary.json"),
          new ObjectMapper().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
    }

    private String status(Exception failure) {
      if (!assertionsPassed || failure != null) {
        return "FAIL";
      }
      return retain ? "RETAINED" : "PASS";
    }

    private Exception recordFailure(Exception primary, Exception secondary) {
      final boolean signalled = Thread.interrupted();
      cleanupInterrupted =
          cleanupInterrupted || signalled || secondary instanceof InterruptedException;
      if (primary == null) {
        return secondary;
      }
      primary.addSuppressed(secondary);
      return primary;
    }
  }
}
