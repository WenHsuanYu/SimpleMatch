package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Respects canonical retries while requiring successful terminal execution and owned evidence. */
final class KubernetesJobs {
  private KubernetesJobs() {}

  static void awaitComplete(KubernetesClient client, String name, VerificationContext run)
      throws Exception {
    final JsonNode terminal;
    try {
      terminal = awaitTerminal(client, name);
    } catch (Exception primary) {
      captureFailure(client, name, run, primary);
      throw primary;
    }
    final var pods = new KubernetesDiagnostics(client, run, Duration.ofSeconds(30)).job(terminal);
    if (condition(terminal, "Failed") || !hasSuccessfulAttempt(pods)) {
      throw new IllegalStateException("Kubernetes Job did not execute successfully: " + name);
    }
  }

  private static JsonNode awaitTerminal(KubernetesClient client, String name) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    do {
      final var job = client.read("get", "job", name, "-o", "json");
      if (condition(job, "Complete") || condition(job, "Failed")) {
        return job;
      }
      Thread.sleep(Duration.ofSeconds(1));
    } while (System.nanoTime() < deadline);
    throw new TimeoutException("Kubernetes Job completion deadline exceeded: " + name);
  }

  private static void captureFailure(KubernetesClient client, String name, VerificationContext run,
      Exception primary) {
    final boolean interrupted = Thread.interrupted();
    try {
      new KubernetesDiagnostics(client, run, Duration.ofSeconds(30))
          .job(client.readWithin(Duration.ofSeconds(10), "get", "job", name, "-o", "json"));
    } catch (Exception diagnostic) {
      primary.addSuppressed(diagnostic);
    } finally {
      if (interrupted || primary instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static boolean hasSuccessfulAttempt(JsonNode pods) {
    for (var pod : pods.path("items")) {
      if ("Succeeded".equals(pod.path("status").path("phase").asText())
          && !pod.path("status").path("containerStatuses").isEmpty()) {
        final boolean zero = java.util.stream.StreamSupport.stream(
            pod.path("status").path("containerStatuses").spliterator(), false)
            .allMatch(status ->
                status.path("state").path("terminated").path("exitCode").asInt(-1) == 0);
        if (zero) {
          return true;
        }
      }
    }
    return false;
  }

  static boolean condition(JsonNode job, String type) {
    return java.util.stream.StreamSupport.stream(
        job.path("status").path("conditions").spliterator(), false).anyMatch(value ->
        type.equals(value.path("type").asText()) && "True".equals(value.path("status").asText()));
  }
}
