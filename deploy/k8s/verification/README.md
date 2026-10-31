# Verification-only Kubernetes resources

This directory contains repository-owned Kubernetes resources that are applied only by verification harnesses. They are deliberately excluded from the normal application overlays under
`deploy/k8s/overlays/`.

The separation is intentional:

- application overlays describe the SimpleMatch runtime;
- verification manifests describe bounded, disposable test workloads that observe that runtime;
- environment adapters provide run-specific data and lifecycle control without embedding Kubernetes
  YAML or duplicating deployment policy.

## RM-1 Risk-to-Matching verifier

`risk-matching-e2e-verifier-job.yaml` is the stable Kubernetes contract for
the `verifyKubernetes` Jupiter entry in `tools/kubernetes-tests`.

The manifest owns:

- the dedicated verifier image;
- `Job` retry/deadline semantics;
- Pod/container security contexts;
- resource requests and limits;
- Market Reference mounts;
- the writable `/tmp` volume used for verifier evidence;
- the exact Java CLI argument wiring.

Run-specific facts are not templated into this file. The environment adapter creates one immutable
`risk-matching-e2e-run` ConfigMap containing:

- `SIMPLEMATCH_RM1_TRADING_DAY`;
- `SIMPLEMATCH_RM1_ACCOUNT_ID`;
- `SIMPLEMATCH_RM1_SIDE`;
- `SIMPLEMATCH_RM1_RUN_ID`;
- `SIMPLEMATCH_RM1_TIMEOUT_SECONDS`.

The Job consumes that ConfigMap through `envFrom`, and its argument list references those variables.
This keeps deployment policy reviewable while preserving a strict boundary between static Kubernetes
configuration and per-run test data.

Before creating the Job, the environment binds source and image identities to the selected Docker
daemon, validates the exact four-node topology, and executes each selected runtime image on all
three workers. It checks actual deployed image identities before and after the business assertion.
Each run has a new namespace, account UUID, evidence directory and ownership receipt. The
`matching-open-barrier-job.yaml` resource publishes native Open Barriers for the same approved
artifact before Risk is started. See the [RM-1 runbook](../../../docs/verification/risk-matching-command-e2e.md)
for supported cluster modes and exact retained-run cleanup.

Validate the manifest contract with:

```bash
scripts/test-risk-matching-e2e-manifest.sh
```

Do not add production credentials or Secrets to verification ConfigMaps. If a future verifier needs
credentials, add a narrowly scoped Secret/RBAC contract rather than extending this run ConfigMap.
