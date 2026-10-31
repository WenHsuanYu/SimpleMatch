package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Collects only bounded current namespace, failing workload and Job observations. */
final class KubernetesDiagnostics {
  private final KubernetesClient client;
  private final VerificationContext run;
  private final long deadline;
  private final ObjectMapper json = new ObjectMapper();
  private final List<Exception> failures = new ArrayList<>();
  private boolean interrupted;

  KubernetesDiagnostics(KubernetesClient client, VerificationContext run, Duration budget) {
    this.client = client;
    this.run = run;
    deadline = System.nanoTime() + budget.toNanos();
  }

  static void collect(KubernetesClient client, VerificationContext run) throws Exception {
    final var diagnostics = new KubernetesDiagnostics(client, run, Duration.ofSeconds(90));
    try {
      diagnostics.collect();
    } finally {
      if (diagnostics.interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void collect() throws Exception {
    for (var entry : Map.of("events.json", List.of("get", "events", "-o", "json"),
        "workloads.json", List.of("get", "deployments,statefulsets,jobs", "-o", "json"))
        .entrySet()) {
      attempt(() -> write("diagnostics-" + entry.getKey(), query(entry.getValue())));
    }
    attempt(() -> {
      final var pods = query(List.of("get", "pods", "-o", "json"));
      write("diagnostics-pods.json", pods);
      for (var pod : pods.path("items")) {
        if (run.runId().equals(pod.path("metadata").path("labels")
            .path(OwnedNamespace.OWNER_LABEL).asText())
            && pod.path("metadata").path("ownerReferences").findValuesAsText("kind")
                .stream().noneMatch("Job"::equals)
            && !(pod.path("metadata").path("name").asText().startsWith("image-probe-")
                && "Succeeded".equals(pod.path("status").path("phase").asText()))) {
          attempt(() -> log(pod, "diagnostics-" + pod.path("metadata").path("name").asText()));
        }
      }
    });
    attempt(() -> {
      for (var job : query(List.of("get", "jobs", "-o", "json")).path("items")) {
        if (run.runId().equals(job.path("metadata").path("labels")
            .path(OwnedNamespace.OWNER_LABEL).asText())) {
          attempt(() -> job(job));
        }
      }
    });
    if (!failures.isEmpty()) {
      final Exception first = failures.get(0);
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
  }

  JsonNode job(JsonNode job) throws Exception {
    final String name = job.path("metadata").path("name").asText();
    final String uid = job.path("metadata").path("uid").asText();
    if (uid.isBlank() || !run.runId().equals(job.path("metadata").path("labels")
        .path(OwnedNamespace.OWNER_LABEL).asText())) {
      throw new IllegalStateException("Job diagnostic ownership changed: " + name);
    }
    write(name + "-job.json", job);
    final var pods = query(List.of("get", "pods", "-l", "job-name=" + name, "-o", "json"));
    write(name + "-pods.json", pods);
    for (var pod : pods.path("items")) {
      requirePodOwner(pod, uid, name);
      if ("Failed".equals(pod.path("status").path("phase").asText())
          || (!KubernetesJobs.condition(job, "Complete")
              && !"Succeeded".equals(pod.path("status").path("phase").asText()))) {
        log(pod, name + "-" + pod.path("metadata").path("name").asText());
      }
    }
    return pods;
  }

  private static void requirePodOwner(JsonNode pod, String uid, String name) {
    final boolean owned = java.util.stream.StreamSupport.stream(
        pod.path("metadata").path("ownerReferences").spliterator(), false)
        .anyMatch(owner -> "Job".equals(owner.path("kind").asText())
          && uid.equals(owner.path("uid").asText()));
    if (!owned) {
      throw new IllegalStateException("Job Pod belongs to a replaced owner: " + name);
    }
  }

  private void log(JsonNode pod, String file) throws Exception {
    for (String field : List.of("initContainerStatuses", "containerStatuses")) {
      for (var status : pod.path("status").path(field)) {
        final String container = status.path("name").asText();
        if (status.path("state").has("running") || status.path("state").has("terminated")) {
          containerLog(pod, container, false, file);
        }
        if (status.path("lastState").has("terminated")) {
          containerLog(pod, container, true, file);
        }
      }
    }
  }

  private void containerLog(JsonNode pod, String container, boolean previous, String file)
      throws Exception {
    final var result = client.execute("", remaining(), "logs",
        pod.path("metadata").path("name").asText(), "--container", container,
        "--previous=" + previous, "--prefix=true", "--tail=100", "--limit-bytes=131072");
    Files.writeString(run.evidence().resolve(file + ".log"),
        "container=" + container + " previous=" + previous + "\n"
            + DiagnosticText.redact(result.output() + result.error()),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    result.checked();
  }

  private JsonNode query(List<String> arguments) throws Exception {
    return client.readWithin(remaining(), arguments.toArray(String[]::new));
  }

  private void write(String file, JsonNode document) throws Exception {
    Files.writeString(run.evidence().resolve(file),
        DiagnosticText.redact(json.writeValueAsString(document)));
  }

  private Duration remaining() throws TimeoutException {
    final long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new TimeoutException("namespace diagnostics exceeded their aggregate budget");
    }
    return Duration.ofNanos(Math.min(nanos, Duration.ofSeconds(15).toNanos()));
  }

  private void attempt(Observation observation) {
    try {
      observation.collect();
    } catch (Exception failure) {
      interrupted |= Thread.interrupted() || failure instanceof InterruptedException;
      failures.add(failure);
    }
  }

  @FunctionalInterface
  private interface Observation {
    void collect() throws Exception;
  }
}
