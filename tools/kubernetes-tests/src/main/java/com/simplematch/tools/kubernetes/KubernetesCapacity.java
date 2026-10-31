package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Checks the actual Docker memory pool without reserving Desktop guest memory twice. */
final class KubernetesCapacity {
  private static final long GIB = 1_073_741_824L;
  private static final String PROBE_LABEL = "io.simplematch.verification.memory-probe";
  private final BoundedCommand commands;
  private final DockerContext context;

  KubernetesCapacity(BoundedCommand commands, DockerContext context) {
    this.commands = commands;
    this.context = context;
  }

  void require(JsonNode info, JsonNode budget, Set<String> platformImages) throws Exception {
    // The verifier adds 512 MiB; kind and its control plane retain their 4 GiB reserve.
    final long required = Math.max(budget.path("peak_phase_memory_bytes").asLong(),
        budget.path("steady_memory_bytes").asLong() + GIB / 2) + 4 * GIB;
    final long total = info.path("MemTotal").asLong();
    final int cpus = info.path("NCPU").asInt();
    final boolean desktop = "desktop-linux".equals(context.name());
    final long host = available(Files.readString(Path.of("/proc/meminfo")));
    new Snapshot(host, desktop ? total : host, total, cpus).require(required, desktop);
    if (desktop) {
      final long guest = guestAvailable(platformImages);
      new Snapshot(available(Files.readString(Path.of("/proc/meminfo"))), guest, total, cpus)
          .require(required, true);
    }
  }

  private long guestAvailable(Set<String> platformImages) throws Exception {
    final String reference = platformImages.stream().filter(image -> image.startsWith("postgres:"))
        .findFirst().orElseThrow(() -> new IllegalStateException(
            "profile has no memory observer image"));
    commands.run(context.command("pull", "--platform", "linux/amd64", reference), "",
        Duration.ofMinutes(3)).checked();
    final String image = commands.run(context.command("image", "inspect", reference,
        "--format", "{{.Id}}"), "", Duration.ofSeconds(15)).checked().trim();
    if (!image.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalStateException("memory observer image has no immutable identity");
    }
    try (var probe = new MemoryProbe(commands, context, UUID.randomUUID().toString())) {
      return available(probe.read(image));
    }
  }

  private static long available(String memory) {
    final var matcher = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+)\\s+kB$").matcher(memory);
    if (!matcher.find()) {
      throw new IllegalStateException("memory observation has no MemAvailable value");
    }
    return Math.multiplyExact(Long.parseLong(matcher.group(1)), 1024);
  }

  /** Separates host process headroom from the memory pool that will execute the workloads. */
  record Snapshot(long hostAvailable, long runtimeAvailable, long dockerTotal, int cpus) {
    void require(long required, boolean desktop) {
      // kind's 4 GiB reserve is already inside required; host tools keep separate headroom.
      final long hostRequired = desktop ? GIB : required;
      if (hostAvailable < hostRequired || runtimeAvailable < required
          || dockerTotal < required || cpus < 8) {
        throw new IllegalStateException(String.format(Locale.ROOT,
            "RM-1 requires %.3f GiB in the Docker runtime including control-plane reserve; "
                + "runtime available %.3f GiB, Docker total %.3f GiB, "
                + "host available %.3f GiB (minimum %.3f GiB), CPUs %d (minimum 8).",
            (double) required / GIB, (double) runtimeAvailable / GIB, (double) dockerTotal / GIB,
            (double) hostAvailable / GIB, (double) hostRequired / GIB, cpus));
      }
    }
  }

  /** Owns only a new read-only observer and its disposable anonymous image volumes. */
  private record MemoryProbe(BoundedCommand commands, DockerContext context, String owner)
      implements AutoCloseable {
    private String read(String image) throws Exception {
      return commands.run(context.command("run", "--rm", "--pull", "never", "--name",
          "simplematch-memory-" + owner, "--label", PROBE_LABEL + "=" + owner, "--memory", "32m",
          "--cpus", "0.1", "--pids-limit", "16", "--network", "none", "--read-only",
          "--entrypoint", "/bin/cat", image, "/proc/meminfo"), "",
          Duration.ofSeconds(30)).checked();
    }

    @Override
    public void close() throws Exception {
      final boolean interrupted = Thread.interrupted();
      try {
        final String remaining = commands.run(context.command("container", "ls", "--all",
            "--no-trunc", "--filter", "label=" + PROBE_LABEL + "=" + owner,
            "--format", "{{.ID}}"), "", Duration.ofSeconds(15)).checked();
        for (String id : remaining.lines().filter(line -> !line.isBlank()).toList()) {
          if (!id.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("memory observer has no exact cleanup identity");
          }
          commands.run(context.command("container", "rm", "--force", "--volumes", id), "",
              Duration.ofSeconds(15)).checked();
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
