package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Revalidates and cleans an explicitly selected run without upgrading its old result. */
public final class KubernetesRetainedCleanup {
  private KubernetesRetainedCleanup() {}

  /** Executes a fresh cleanup-only report after checking the recorded daemon and ownership. */
  public static void main(String[] arguments) throws Exception {
    final Path root = requiredPath("verificationRepoRoot");
    final var owner = KubernetesOwnership.read(requiredPath("kubernetesOwnership"),
        KubernetesSettings.coordinationDirectory());
    final var request = new VerificationRequest(requiredPath("kubernetesCleanupEvidence"),
        KubernetesSettings.coordinationDirectory(), false);
    VerificationRun.verify(request, new RetainedResources(root, owner), run -> {});
  }

  private static Path requiredPath(String property) {
    final String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("required cleanup path: " + property);
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static final class RetainedResources implements VerificationResources {
    private final Path root;
    private final KubernetesOwnership owner;
    private final BoundedCommand commands;
    private final Optional<OwnedKindCluster> cluster;
    private KubernetesClient client;
    private boolean gone;

    private RetainedResources(Path root, KubernetesOwnership owner) {
      this.root = root;
      this.owner = owner;
      commands = owner.docker().context().commands(root);
      cluster = owner.mode().mayDelete(owner.cluster().created(), owner.cluster().prepared())
          ? Optional.of(new OwnedKindCluster(commands, owner.docker().context(), owner.cluster(),
              owner.kubeconfig(), owner.runId())) : Optional.empty();
    }

    @Override
    public String preflight() throws Exception {
      owner.docker().requireCurrent(commands);
      return "docker:" + owner.docker().daemonId();
    }

    @Override
    public void create(VerificationContext run) throws Exception {
      gone = cluster.isPresent() && cluster.orElseThrow().isGone();
      if (gone || !owner.cluster().prepared()) {
        return;
      }
      if (Files.isSymbolicLink(owner.kubeconfig())
          || !Files.isRegularFile(owner.kubeconfig(), LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("original private kubeconfig is unavailable");
      }
      client = new KubernetesClient(root, owner.kubeconfig(), "kind-" + owner.cluster().name(),
          owner.namespace());
    }

    @Override
    public void diagnose(VerificationContext run) throws Exception {
      Files.write(run.evidence().resolve("cleanup-target.json"),
          new ObjectMapper().writeValueAsBytes(
          Map.of("verification_scope", "retained-run cleanup only", "source_run_id", owner.runId(),
              "cluster", owner.cluster().name(), "namespace", owner.namespace(),
              "namespace_uid", owner.namespaceUid(), "already_absent", gone)));
      if (client != null) {
        KubernetesDiagnostics.collect(client,
            new VerificationContext(owner.runId(), run.evidence()));
      }
    }

    @Override
    public void cleanup(VerificationContext run) throws Exception {
      owner.docker().requireCurrent(commands);
      if (gone) {
        return;
      }
      KubernetesTeardown.clean(() -> {
        if (owner.cluster().prepared()) {
          if (client == null) {
            throw new IllegalStateException("original client is unavailable for namespace cleanup");
          }
          KubernetesNamespace.retained(client, owner).cleanup(run.evidence());
        }
      }, () -> {
        if (cluster.isPresent()) {
          cluster.orElseThrow().delete();
        }
      });
    }
  }
}
