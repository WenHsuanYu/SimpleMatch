package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns a fresh business environment and its independent namespace/node cleanup boundaries. */
final class KubernetesBusinessEnvironment implements VerificationResources {
  private final KubernetesSettings settings = KubernetesSettings.selected();
  private final ObjectMapper json = new ObjectMapper();
  private final BoundedCommand commands = settings.dockerContext().commands(settings.root());
  private KubernetesPrerequisites inputs;
  private KubernetesImages images;
  private KubernetesWorkloadImages workloadImages;
  private KindEnvironment kind;
  private KubernetesNamespace namespace;
  private KubernetesScenarioTarget target;

  private final KubernetesFixCredentials credentials;
  private final KubernetesGatewayAdmission admission;
  private final KubernetesQueryCredentials queryCredentials;

  KubernetesBusinessEnvironment() {
    credentials = null;
    admission = null;
    queryCredentials = null;
  }

  KubernetesBusinessEnvironment(KubernetesFixCredentials credentials, UUID account) {
    this.credentials = java.util.Objects.requireNonNull(credentials);
    admission = new KubernetesGatewayAdmission();
    queryCredentials = new KubernetesQueryCredentials(account);
  }

  PublicOrder publicOrder() {
    return new PublicOrder(settings, inputs.input(), target, admission, queryCredentials,
        workloadImages);
  }

  VerificationRequest request() {
    return new VerificationRequest(settings.evidence(), settings.coordination(), settings.retain());
  }

  RiskCommandProbe.Facts submitAndObserve(VerificationContext run) throws Exception {
    final var facts = new RiskCommandProbe(settings, inputs, target).execute();
    verifyRuntime(run);
    return facts;
  }

  @Override
  public String preflight() throws Exception {
    final String expected = credentials == null ? "risk-command" : "resting-buy";
    if (!expected.equals(settings.scenario())) {
      throw new IllegalArgumentException(
          "selected Jupiter scenario differs from the build profile");
    }
    inputs = KubernetesPrerequisites.observe(settings);
    images = KubernetesImages.observe(settings, inputs.profile());
    KubernetesBuildInputs.requireCurrent(inputs, images);
    return "docker:" + inputs.daemonId();
  }

  @Override
  public void create(VerificationContext run) throws Exception {
    inputs.docker().requireCurrent(commands);
    requireVerificationSource();
    Files.write(run.evidence().resolve("preflight.json"), json.writeValueAsBytes(Map.of(
        "verification_scope", credentials == null ? "RM-1 BUY admission/outbox to matching.commands"
            : "public FIX BUY to Matching, Persistence, Account and FIX delivery",
        "source_fingerprint", inputs.sourceFingerprint(), "daemon_id", inputs.daemonId(),
        "verification_source_fingerprint", inputs.verificationFingerprint(),
        "docker_context", settings.dockerContext().name(), "mode", settings.mode().value(),
        "resource_budget", inputs.budget(), "trading_day", settings.day().toString(),
        "artifact_sha256", inputs.input().artifact().identity().contentSha256())));
    kind = new KindEnvironment(settings, run, inputs.docker());
    kind.prepare();
    namespace = new KubernetesNamespace(
        kind.client(), kind.namespace(), run.runId(), kind.state().uid());
    namespace.create();
    kind.writeOwnership(namespace.uid());
    workloadImages = images.loadAndProbe(kind, run);
    requireStorageClass();
    target = new KubernetesScenarioTarget(kind.client(), kind.namespace(), run,
        images.replacements(settings.tag()));
    new RiskCommandDeployment(settings, inputs, images, target,
        KindApiEgress.controlPlaneIp(kind.client(), kind.cluster()))
        .start(credentials, admission, queryCredentials);
    workloadImages.verify(kind.client(), run, "before-admission");
  }

  private void requireStorageClass() throws Exception {
    final var expected = new ObjectMapper(new YAMLFactory()).readTree(settings.root()
        .resolve("deploy/kind/simplematch-live-storageclass.yaml").toFile());
    if (kind.client().find("get", "storageclass", "simplematch-rwo-pod", "--ignore-not-found",
        "-o", "json").isEmpty()) {
      kind.client().create(expected);
    }
    final var current = kind.client().read(
        "get", "storageclass", "simplematch-rwo-pod", "-o", "json");
    for (String key : List.of("provisioner", "reclaimPolicy", "volumeBindingMode")) {
      if (!expected.path(key).equals(current.path(key))) {
        throw new IllegalStateException("canonical StorageClass differs: " + key);
      }
    }
  }

  @Override
  public void diagnose(VerificationContext run) throws Exception {
    inputs.docker().requireCurrent(commands);
    if (kind == null) {
      Files.writeString(run.evidence().resolve("diagnostics-setup.txt"),
          "No cluster creation was attempted. The primary failure is recorded in summary.json.\n");
    } else if (!kind.state().prepared()) {
      kind.diagnoseSetup();
    } else {
      KubernetesDiagnostics.collect(kind.client(), run);
    }
  }

  @Override
  public void cleanup(VerificationContext run) throws Exception {
    inputs.docker().requireCurrent(commands);
    final var cluster = kind == null
        ? java.util.Optional.<OwnedKindCluster>empty() : kind.cleanupTarget();
    if (cluster.isPresent() && cluster.orElseThrow().isGone()) {
      return;
    }
    KubernetesTeardown.clean(() -> {
      if (namespace != null) {
        namespace.cleanup(run.evidence());
      }
    }, () -> {
      if (cluster.isPresent()) {
        cluster.orElseThrow().delete();
      }
    });
  }

  void verifyRuntime(VerificationContext run) throws Exception {
    workloadImages.verify(kind.client(), run, "after-business");
    requireVerificationSource();
  }

  private void requireVerificationSource() throws Exception {
    if (!inputs.verificationFingerprint().equals(
        KubernetesSource.verificationFingerprint(settings.root(), commands))) {
      throw new IllegalStateException("verification source changed during the run");
    }
  }

  record PublicOrder(KubernetesSettings settings, KubernetesInput input,
      KubernetesScenarioTarget target, KubernetesGatewayAdmission admission,
      KubernetesQueryCredentials queryCredentials, KubernetesWorkloadImages workloadImages) {}
}
