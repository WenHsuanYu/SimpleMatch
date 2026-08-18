# Verification-only Kubernetes resources

This directory contains repository-owned Kubernetes resources that are applied only by retained
verification harnesses. They are deliberately excluded from the normal application overlays under
`deploy/k8s/overlays/`.

The separation is intentional:

- application overlays describe the SimpleMatch runtime;
- verification manifests describe bounded, disposable test workloads that observe that runtime;
- orchestration scripts provide run-specific data and lifecycle control without embedding Kubernetes
  YAML or duplicating deployment policy.

## RM-1 Risk-to-Matching verifier

`risk-matching-e2e-verifier-job.yaml` is the stable Kubernetes contract for
`scripts/run-risk-matching-command-e2e.sh`.

The manifest owns:

- the dedicated verifier image;
- `Job` retry/deadline semantics;
- Pod/container security contexts;
- resource requests and limits;
- Market Reference mounts;
- the writable `/tmp` volume used for verifier evidence;
- the exact Java CLI argument wiring.

Run-specific facts are not templated into this file. The orchestration script creates one immutable
`risk-matching-e2e-run` ConfigMap containing:

- `SIMPLEMATCH_RM1_TRADING_DAY`;
- `SIMPLEMATCH_RM1_ACCOUNT_ID`;
- `SIMPLEMATCH_RM1_RUN_ID`;
- `SIMPLEMATCH_RM1_TIMEOUT_SECONDS`.

The Job consumes that ConfigMap through `envFrom`, and its argument list references those variables.
This keeps deployment policy reviewable while preserving a strict boundary between static Kubernetes
configuration and per-run test data.

Validate the manifest contract with:

```bash
scripts/test-risk-matching-e2e-manifest.sh
```

Do not add production credentials or Secrets to verification ConfigMaps. If a future verifier needs
credentials, add a narrowly scoped Secret/RBAC contract rather than extending this run ConfigMap.
