# Local Certification Phase DAG and Content-Addressed Evidence

Status: Implemented

This specification defines an incremental execution model for
`run-local-production-like-certification.sh`. The goal is to reduce repeated
local certification time without weakening the meaning of a `PASSED` result.
It replaces coarse run-level reuse with a phase dependency graph and
content-addressed evidence while preserving fresh runtime verification where
state freshness is itself part of the requirement.

The specification changes verification execution, not trading-domain
terminology. It therefore does not add entries to `CONTEXT.md`.

## 1. Problem

The current production-like runner executes a largely linear workflow:

1. validate static Kubernetes, Matching, Flyway, Compose, and image inventory
   contracts;
2. build the complete local image inventory;
3. start a Compose verification fixture;
4. verify Kafka producer, capacity, fixture, and broker-failure behavior;
5. tear down the Compose fixture;
6. prepare or publish the complete Kubernetes image inventory;
7. create a fresh certification namespace;
8. apply inputs, platform resources, migrations, topics, and Open Barriers;
9. deploy workloads and wait for them to become usable;
10. verify the 15-owner Matching fleet;
11. record retained-run provenance for dependent certification.

This is deliberately conservative, but it gives unrelated work the same
freshness lifetime. A change to one certification script can cause images,
static checks, and other independent work to be repeated even when their exact
inputs and immutable outputs are unchanged.

The existing `--resume` support is also broader than the intended incremental
model. It is designed to continue the same run and retained namespace. It must
not become the mechanism for silently reusing outputs across independent runs.

## 2. Goals

The design MUST:

- keep the normal operator entry point small;
- produce `PASSED`, not `PARTIAL`, when previously generated evidence is safely
  reused;
- create a fresh Kubernetes runtime for phases whose correctness depends on
  fresh state;
- rebuild or republish only artifacts whose effective inputs changed or whose
  immutable output can no longer be verified;
- record why every phase was executed, reused, or revalidated;
- make invalidation deterministic and reviewable;
- prevent stale, corrupted, missing, or mismatched evidence from being treated
  as success;
- keep retained production-like evidence self-contained enough for dependent
  certification;
- preserve current source, namespace, image, and trading-day provenance;
- fail closed when dependency identity, evidence identity, or output identity
  cannot be established;
- keep cleanup scoped to run-owned disposable resources and cache objects that
  are not referenced by an active retained run.

The design SHOULD expose independent phases as a DAG so later implementations
can use bounded parallel execution where resource pressure permits it.

## 3. Non-goals

This specification does NOT:

- introduce a separate `run-fast-certification.sh` workflow;
- make `--skip-build`, `--skip-compose`, or `--skip-kubernetes` equivalent to
  verified reuse;
- reuse PostgreSQL data, Kafka offsets, Matching ownership state, Gateway gate
  state, or critical-consumer progress across fresh production-like runs;
- treat a Git commit SHA, mutable Docker tag, timestamp, or previous PASS alone
  as sufficient reuse evidence;
- require distributed or remote cache infrastructure;
- require build-artifact reuse across different machines in the first
  implementation;
- make environment-fault evidence reusable before a reliable environment
  identity and revalidation rule exist;
- add another implementation of Kubernetes lifecycle, image inventory, or
  deployment rendering.

## 4. Terminology

These terms are local verification terms, not trading-domain terms.

**Phase** — one certification action with declared dependencies, inputs,
outputs, and a reuse policy.

**Phase DAG** — the directed acyclic graph formed by phase dependency edges.
A phase may run only after every required predecessor has produced valid
current-run or reusable evidence.

**Input fingerprint** — a SHA-256 digest over the canonical representation of
all inputs that can affect a phase result.

**Artifact identity** — an immutable content identity for an output, such as an
OCI digest or SHA-256 file digest.

**Evidence record** — an immutable record proving that a phase passed for one
input fingerprint and produced specific output identities.

**Evidence object** — the serialized evidence record stored by content digest.

**Evidence index** — a lookup from `(phase ID, input fingerprint)` to an
evidence-object digest. The index is a performance aid, not a trust source.

**Run evidence** — evidence materialized into one certification run directory.
It records the plan and the result used for this run, including reused evidence.

**Fresh phase** — a phase that must execute for every new production-like run.

**Reusable phase** — a phase whose prior PASS may be used when its exact input
fingerprint and immutable outputs still validate.

**Revalidated phase** — a phase that reuses a prior expensive result only after
performing a current, phase-specific external validity check.

## 5. Design principles

### 5.1 Reuse is proof, not skipping

`REUSE` means:

> An immutable prior evidence record proves this requirement for exactly the
> current effective inputs, and every required output identity still validates.

`SKIP` means:

> The operator chose not to prove this requirement in this run.

They MUST remain different states. A normal cached run may still be `PASSED`.
A run with a required phase explicitly skipped retains the existing `PARTIAL`
semantics.

### 5.2 Fresh runtime and fresh build are different requirements

A new production-like certification MUST receive a new runtime state where the
verification requirement depends on state freshness. This does not imply that
an unchanged OCI image must be rebuilt from identical source inputs.

### 5.3 Content identity wins over location and time

Evidence reuse MUST be based on canonical input content and immutable output
identity. It MUST NOT depend on:

- file modification time;
- absolute workspace path;
- the age of a previous PASS by itself;
- a mutable image tag such as `:local`;
- a Git commit SHA as a substitute for effective build inputs.

The current Git revision remains important provenance metadata, but it is not a
universal cache key.

### 5.4 Over-invalidation is safe; under-invalidation is not

The first implementation MAY conservatively include more source files in a
phase fingerprint than strictly necessary. That costs execution time but does
not weaken certification.

A phase fingerprint MUST NOT omit an input that can alter the phase result.

### 5.5 The cache is untrusted

The evidence cache is an optimization. Every read MUST validate content hashes,
schema, phase identity, input identity, and required outputs. Corruption or
missing data causes cache rejection and phase execution, not certification
success.

## 6. Module design

The external interface remains the existing certification command:

```bash
scripts/run-local-production-like-certification.sh --keep-resources
```

No `--fast` mode is introduced.

The implementation is divided into four modules behind the runner.

### 6.1 `PhaseGraph`

Interface responsibility:

- enumerate phase IDs;
- declare dependency edges;
- declare each phase reuse policy;
- declare the inputs and outputs owned by each phase;
- reject missing dependencies and cycles before execution.

The caller must not encode dependency ordering independently.

Conceptual interface:

```text
phase_graph() -> PhaseGraph
phase_definition(phase_id) -> PhaseDefinition
```

### 6.2 `PhaseFingerprint`

Interface responsibility:

- canonicalize source-file inputs, scalar configuration, tool identities,
  upstream artifact identities, and environment identity where applicable;
- return a deterministic SHA-256 input fingerprint;
- explain the input set used to produce that fingerprint.

Conceptual interface:

```text
fingerprint(phase_definition, resolved_inputs) -> Fingerprint
```

Fingerprint computation is pure. It MUST NOT execute the phase.

### 6.3 `EvidenceStore`

Interface responsibility:

- find candidate evidence by phase ID and input fingerprint;
- validate evidence-object integrity;
- validate referenced file and image identities;
- atomically publish immutable evidence records;
- materialize the evidence required by the current run;
- never mutate an existing evidence object.

Conceptual interface:

```text
find_valid(phase_id, input_fingerprint) -> Evidence | MISS
put(evidence_record) -> evidence_digest
materialize(evidence_digest, run_directory) -> RunEvidenceReference
```

### 6.4 `CertificationPlanner`

Interface responsibility:

- resolve the Phase DAG for the requested certification profile;
- compute phase fingerprints after predecessor identities are known;
- choose `EXECUTE`, `REUSE`, or `REVALIDATE` for every required phase;
- never choose `REUSE` for a `FRESH` phase;
- produce a human-readable reason for every decision;
- stop planning if a required dependency has no valid execution path.

Conceptual interface:

```text
plan(run_inputs, phase_graph, evidence_store) -> CertificationPlan
```

The planner owns reuse decisions. Individual callers and top-level orchestration
MUST NOT duplicate cache rules.

## 7. Phase model

Each phase definition MUST contain at least:

```json
{
  "id": "local-image-build/quickfix-gateway",
  "definitionVersion": 1,
  "dependencies": [],
  "reusePolicy": "CONTENT_ADDRESSED",
  "inputKinds": ["source", "configuration", "toolchain"],
  "outputKinds": ["oci-image"]
}
```

`definitionVersion` changes only when phase semantics change in a way that is
not already represented by the declared implementation inputs. The phase's own
implementation files MUST also be part of its effective input set.

The required policies are:

- `FRESH` — execute for every new production-like run;
- `CONTENT_ADDRESSED` — reuse only when exact input and output identities
  validate;
- `REVALIDATE` — reuse the expensive result only after a current external
  validity check succeeds.

No implicit default policy is allowed. Every phase MUST declare one.

## 8. Canonical input fingerprints

### 8.1 Canonical input manifest

Before hashing, a phase produces a canonical input manifest. Conceptually:

```json
{
  "schemaVersion": 1,
  "phaseId": "local-image-build/quickfix-gateway",
  "definitionVersion": 1,
  "files": [
    {
      "path": "services/quickfix-gateway/build.gradle.kts",
      "sha256": "...",
      "mode": "100644"
    }
  ],
  "values": {
    "imagePlatform": "linux/amd64"
  },
  "upstreamArtifacts": [],
  "toolchain": {
    "gradle": "9.7.0"
  }
}
```

The serialized representation MUST have deterministic key ordering and list
ordering before hashing.

### 8.2 File rules

For a file input, the fingerprint MUST include:

- repository-relative path;
- content SHA-256;
- executable/non-executable mode when it affects execution.

For a symlink, include the repository-relative path and link target rather than
the target file contents unless the target is independently declared as an
input.

Absolute paths and mtimes MUST NOT be hashed.

### 8.3 Source dependency closure

Image fingerprints MUST include every source and build input that can affect
the image. The initial implementation SHOULD intentionally over-approximate the
closure rather than invent a fragile fine-grained dependency analyzer.

For Spring images, a conservative first closure should include:

- the owning Gradle project source and resources;
- project dependencies that are packaged into that service;
- shared protobuf/schema inputs used by those projects;
- root Gradle configuration and version catalog inputs that affect resolution;
- dependency locks or verified dependency checksums where the build uses them;
- build logic used by `bootBuildImage`;
- the resolved immutable builder-image and run-image identities, including
  defaults selected by the build when no explicit override is supplied.

For Dockerfile-built images, include:

- Dockerfile contents;
- every repository file copied or generated into the build context by that
  Dockerfile;
- dependency manifests and lockfiles affecting the build;
- the resolved immutable identity of every base image that can affect output,
  even when the Dockerfile names that base through a mutable tag.

A later implementation MAY narrow these closures after tests prove equivalent
invalidation behavior.

### 8.4 Upstream artifacts

A downstream phase MUST fingerprint the immutable identities of the upstream
outputs it consumes. It must not fingerprint only the upstream phase name or
an execution-specific evidence-object digest.

For example, Kubernetes manifest rendering should depend on the complete image
lock's immutable image references, not merely on whether an image-build phase
reported PASS.

`dependencyEvidence` stored in an evidence record is lineage metadata. A
current predecessor may produce a different evidence-object digest because its
execution timestamp or source revision differs while its effective inputs and
immutable outputs remain identical. That alone MUST NOT invalidate downstream
evidence. Downstream invalidation follows declared effective inputs and
artifact identities.

### 8.5 Environment identity

Environment identity is used only by phases whose result depends on the host or
runtime environment. The identity MUST be phase-specific.

A host-wide opaque fingerprint is prohibited because it would hide which fact
invalidated evidence.

## 9. Evidence record

A reusable PASS is represented as immutable JSON. Minimum schema:

```json
{
  "schemaVersion": 1,
  "phaseId": "local-image-build/quickfix-gateway",
  "definitionVersion": 1,
  "inputFingerprint": "sha256:...",
  "dependencyEvidence": [
    "sha256:..."
  ],
  "status": "PASS",
  "execution": {
    "sourceRevision": "<git-sha>",
    "startedAtUtc": "2026-08-28T00:00:00Z",
    "completedAtUtc": "2026-08-28T00:02:00Z",
    "durationMillis": 120000
  },
  "outputs": [
    {
      "kind": "oci-image",
      "name": "quickfix-gateway",
      "identity": "sha256:..."
    }
  ]
}
```

`sourceRevision` and `dependencyEvidence` are audit lineage metadata. Different
values do not invalidate reuse when the phase ID, definition version, exact
input fingerprint, and declared immutable output identities still satisfy the
current plan.

Only `PASS` evidence is reusable. `FAIL`, `PARTIAL`, interrupted, or incomplete
records MUST NOT enter the reusable index.

The evidence-object digest is:

```text
sha256(canonical evidence-record bytes)
```

Two executions with identical effective inputs and outputs may still produce
different evidence-object digests because execution metadata differs. That is
valid; content addressing protects each record's integrity rather than forcing
execution metadata to be identical.

## 10. Evidence store layout

The default local store SHOULD be outside individual run directories:

```text
out/certification-cache/
├── objects/
│   └── sha256/
│       └── ab/
│           └── abcdef....json
├── artifacts/
│   └── sha256/
│       └── ...
├── index/
│   └── v1/
│       └── <phase-id>/
│           └── <input-fingerprint>.ref
└── locks/
```

The location MAY be overridden by
`SIMPLEMATCH_CERTIFICATION_CACHE_DIR`.

The index contains only evidence-object digests. The object is authoritative
only after its own content digest is validated.

All writes MUST use temporary files plus atomic rename. An index entry MUST be
published only after its evidence object and required local artifacts are fully
written and validated.

## 11. Run evidence remains authoritative for dependent certification

A fresh production-like run MUST materialize the evidence it depends on into
its own run directory even when a phase was reused.

Minimum run artifacts:

```text
out/certification/local-production-like-<run-id>/
├── run-context
├── plan.json
├── evidence-manifest.json
├── report.md
├── phases/
│   └── <phase-id>/result.json
├── local-images.lock
├── rendered Kubernetes manifests
└── retained provenance required by dependent certification
```

A phase result records:

```json
{
  "phaseId": "local-image-build/quickfix-gateway",
  "decision": "REUSE",
  "inputFingerprint": "sha256:...",
  "evidenceDigest": "sha256:...",
  "reason": "exact inputs and OCI digest validated"
}
```

The retained namespace and dependent certification MUST NOT require the cache
directory to remain present after the production-like run finishes. Required
provenance, image-lock information, manifests, and evidence records are
materialized into the run directory.

This makes the cache a performance mechanism rather than a hidden correctness
dependency.

## 12. Planner decisions

For each phase in dependency order:

1. verify every required predecessor has valid current-plan evidence;
2. resolve effective phase inputs;
3. calculate the canonical input fingerprint;
4. apply the phase reuse policy;
5. if `FRESH`, choose `EXECUTE`;
6. if reusable, look up `(phase ID, input fingerprint)`;
7. validate the evidence object and all required outputs;
8. for `REVALIDATE`, perform the current external check;
9. choose `REUSE` or `REVALIDATE` only after all checks succeed;
10. otherwise choose `EXECUTE`;
11. materialize the chosen evidence into the current run after success.

A cache miss is not an error. It causes execution.

Invalid cache evidence is not an execution failure unless the phase itself
cannot execute. The planner MUST report why evidence was rejected.

## 13. Required DAG shape

The initial graph should preserve the current behavioral ordering while making
independent prerequisites explicit.

Conceptually:

```text
                         static source checks
                                │
                                ├─────────────┐
                                │             │
                       image fingerprints    compose contract
                                │             │
                 ┌──────────────┼───────┐     │
                 │              │       │     │
             image A         image B   ...    │
                 │              │             │
                 └──────┬───────┘             │
                        │                     │
                  image publication        compose-up
                        │                     │
                  complete image lock      compose-wait
                        │                     │
                        │               Kafka fixture/fault
                        │                     │
                        │                 compose-down
                        │                     │
                        └──────────┬──────────┘
                                   │
                           fresh namespace
                                   │
                          Kubernetes inputs
                                   │
                              platform
                                   │
                              migrations
                                   │
                         topic provisioning
                                   │
                            Open Barriers
                                   │
                              workloads
                                   │
                            workload wait
                                   │
                        15-owner fleet proof
                                   │
                           run provenance
```

The exact graph may contain more nodes, but dependencies MUST be declared in
one graph definition rather than repeated through shell ordering conventions.

## 14. Initial phase policy matrix

The first implementation deliberately keeps environment and Kubernetes runtime
phases conservative.

| Phase or phase family | Initial policy | Required validity rule |
| --- | --- | --- |
| source cleanliness / repository preflight | `FRESH` | Current workspace must satisfy the existing clean-source contract. |
| static Kubernetes overlays | `CONTENT_ADDRESSED` | Exact validation script and manifest inputs match. |
| static Kubernetes dependencies | `CONTENT_ADDRESSED` | Exact dependency-check inputs match. |
| static Matching manifests/profile | `CONTENT_ADDRESSED` | Matching manifests, profile, and validator inputs match. |
| static Flyway service checks | `CONTENT_ADDRESSED` | Flyway definitions and validator inputs match. |
| Compose config rendering | `CONTENT_ADDRESSED` | Compose source and rendering inputs match. |
| local image inventory validation | `CONTENT_ADDRESSED` | Inventory module and relevant configuration match. |
| image build per canonical service | `CONTENT_ADDRESSED` | Exact build-input fingerprint matches and local/registry OCI identity validates. |
| registry publication per image | `REVALIDATE` | Required registry digest remains addressable; otherwise republish the same verified image. |
| complete image-lock construction | `CONTENT_ADDRESSED` | Every selected image digest is valid and the lock is complete. |
| Kafka producer contract generation | `CONTENT_ADDRESSED` | Producer-contract inputs match and generated content digest validates. |
| Kafka capacity evidence | `FRESH` | Capacity is a current dynamic host fact. |
| Compose startup/wait/status | `FRESH` | Current runtime health is part of the proof. |
| Kafka topic/fixture setup | `FRESH` | Uses fresh Compose runtime state. |
| Kafka broker-failure live proof | `FRESH` initially | Do not reuse until environment identity and revalidation are separately specified. |
| Compose teardown | `FRESH` | Cleanup applies to the current run-owned fixture. |
| registry connectivity check | `FRESH` | Cheap current connectivity/integration proof. |
| kind-load node import | `FRESH` | Node-local containerd state is mutable. |
| Kubernetes manifest render/split | `CONTENT_ADDRESSED` | Exact source/config/image-lock/trading-day inputs match. |
| certification namespace creation | `FRESH` | Namespace state must be new and run-owned. |
| Kubernetes inputs/platform apply | `FRESH` | Applied state belongs to the new namespace. |
| migrations | `FRESH` | Must prove migrations against fresh database state. |
| topic provisioning | `FRESH` | Must prove topics in the current runtime. |
| Open Barrier publication | `FRESH` | Session and runtime identity are current-run facts. |
| workload apply/wait | `FRESH` | Runtime availability is current-run behavior. |
| Matching fleet verification | `FRESH` | Ownership, identity, recovery, and readiness are live facts. |
| retained-run provenance | `FRESH` | Binds the current source, run, namespace, and immutable images. |

A later proposal may change an environment phase from `FRESH` to `REVALIDATE`,
but only with an explicit environment identity and acceptance tests proving that
no required current fact is lost.

## 15. Per-image content-addressed reuse

The current single `local-image-build` action should become one logical phase
per canonical image inventory entry.

Examples:

```text
local-image-build/account-service
local-image-build/risk-service
local-image-build/persistence
local-image-build/quickfix-gateway
local-image-build/flyway-runner
local-image-build/risk-matching-e2e-verifier
local-image-build/matching
```

Every phase outputs an OCI image identity. The mutable source tag may still be
used as a local transport convenience, but it MUST NOT be the evidence identity.

A reused image is valid only when:

1. its exact build-input fingerprint matches;
2. prior evidence is a valid PASS record;
3. the expected OCI digest still exists in the selected artifact location;
4. the current image-lock entry resolves to that digest;
5. any transport-specific integrity checks still pass.

For registry transport, the planner may avoid a push when the immutable digest
is already addressable in the repository-managed local registry.

For `kind-load`, image build output may be reused, but import and node-local
execution readiness remain current-run work because containerd state is mutable.

## 16. Incremental complete image lock

Deployment MUST continue to consume one complete immutable image lock.

Incremental preparation is an implementation detail:

```text
verified prior entries
        +
newly built/published entries
        ↓
new complete immutable lock
```

The lock constructor MUST fail if any canonical required service is missing,
duplicated, malformed, or lacks an immutable digest reference.

Callers MUST NOT need to understand whether an individual image was reused or
rebuilt.

## 17. `--resume` semantics

Cross-run content-addressed reuse is automatic and independent of `--resume`.

`--resume` retains one narrow meaning:

> Continue the same interrupted production-like run using the same evidence
> directory, namespace, trading day, transport, and run identity.

Run-local successful `FRESH` phases may be resumed only when their existing
runtime state is explicitly validated by the current resume contract.

A new production-like run does not use `--resume` merely to access the evidence
cache.

This separation prevents same-run lifecycle recovery and cross-run artifact
reuse from becoming one ambiguous mechanism.

## 18. Failure semantics

The implementation MUST fail closed under the following conditions:

- Phase DAG has a cycle, unknown dependency, or duplicate phase ID.
- Fingerprint input cannot be read or canonicalized.
- Evidence-object digest does not match its contents.
- Evidence schema, phase ID, definition version, or input fingerprint differs.
- Referenced output is missing or has a different content identity.
- A `REVALIDATE` external check fails.
- An upstream phase has no valid evidence or successful execution result.
- A reusable phase executes but cannot atomically publish valid evidence.
- A fresh runtime phase cannot prove run ownership or cleanup responsibility.

A rejected cache entry MUST NOT be silently reported as `REUSE`; the report
must state the rejection reason and resulting execution decision.

## 19. Concurrency

The DAG makes parallel execution possible but concurrency is not required for
the first implementation.

When introduced:

- concurrency MUST be bounded;
- phases with shared mutable external state MUST remain serialized;
- image builds MAY run concurrently only under an explicit local resource
  budget;
- cache publication MUST use a lock scoped by `(phase ID, input fingerprint)`
  or equivalent atomic creation semantics;
- concurrent executions may publish distinct immutable evidence objects when
  execution metadata differs, but the index MUST atomically resolve to one
  currently valid object for the input fingerprint;
- a waiting process MUST validate the indexed evidence instead of assuming the
  other process succeeded.

Resource classes such as CPU-heavy image build, Docker mutation, registry
publication, Compose mutation, and Kubernetes mutation SHOULD be declared in
phase metadata before parallel scheduling is enabled.

## 20. Cache cleanup and retained evidence

The cache is disposable; active run evidence is not.

Cleanup MUST:

- never remove evidence or OCI content referenced by a retained production-like
  run that may still be used by dependent certification;
- never use broad Docker or filesystem pruning as a cache policy;
- inventory candidate cache objects before deletion;
- delete only unreferenced repository-owned cache objects;
- treat active run directories as roots when determining references;
- keep post-cleanup evidence sufficient to explain what was removed.

A future top-level cleanup command may remove old cache objects, but cache
management must remain separate from namespace lifecycle cleanup.

## 21. Operator interface and reporting

The normal command remains unchanged:

```bash
scripts/run-local-production-like-certification.sh --keep-resources
```

Before execution, the runner SHOULD print a concise plan:

```text
REUSE   static-kubernetes-overlays
REUSE   local-image-build/account-service
EXECUTE local-image-build/quickfix-gateway
REVALIDATE registry-publish/account-service
EXECUTE kafka-capacity-evidence
EXECUTE kubernetes-namespace
EXECUTE kubernetes-migrations
EXECUTE kubernetes-fleet
```

Every line MUST include a machine-readable equivalent in `plan.json`.

The report MUST distinguish:

- `EXECUTED` — command ran successfully in this run;
- `REUSED` — immutable evidence was accepted without expensive re-execution;
- `REVALIDATED` — prior evidence was accepted after a current external check;
- `SKIPPED` — operator deliberately omitted the phase.

Only `SKIPPED` required work changes the current full certification result to
`PARTIAL`.

## 22. Timing and performance evidence

Phase timing is required before claiming an optimization succeeded.

Every phase result MUST record:

- start time;
- completion time;
- elapsed milliseconds;
- decision type;
- cache lookup/revalidation time where applicable.

`report.md` SHOULD show both:

- actual wall-clock time spent in the current run;
- avoided execution time based on the most recent valid execution duration of
  reused evidence.

The performance target is relative rather than host-specific:

> After a warm cache is established, normal full certification time should
> approach the critical path of phases that are required to remain fresh, plus
> validation overhead. Reusable unchanged phases must not dominate the warm-run
> critical path.

No fixed seconds-based target is specified until baseline timings are collected
on the canonical local environment.

## 23. Required implementation seams for tests

Before implementation tests are written, the following seams are the agreed
behavioral test surfaces.

### 23.1 Phase graph seam

Tests verify:

- graph is acyclic;
- dependencies are complete;
- phase IDs are unique;
- every phase declares a policy;
- required runtime phases remain `FRESH`.

Tests do not assert the internal traversal algorithm.

### 23.2 Fingerprint seam

Tests provide input files/values and assert the resulting identity behavior:

- same effective content gives the same fingerprint;
- content change changes the fingerprint;
- mtime and absolute workspace path do not change it;
- omitted required dependency fixtures demonstrate a failing contract test,
  not an accepted under-invalidation.

Tests do not assert private hash-building steps.

### 23.3 Evidence-store seam

Tests verify observable behavior:

- valid evidence round-trips;
- corrupt object is rejected;
- wrong input fingerprint is rejected;
- missing output is rejected;
- atomic publication leaves no accepted partial record;
- cache miss is distinguishable from invalid evidence.

### 23.4 Planner seam

Tests provide phase definitions, fingerprints, and evidence adapters and assert:

- `FRESH` always executes;
- valid reusable evidence produces `REUSE`;
- invalid reusable evidence produces `EXECUTE`;
- successful external validation produces `REVALIDATE`;
- failed external validation produces `EXECUTE`;
- dependency invalidation propagates only where the downstream effective input
  changes;
- explicit skipped required work remains `PARTIAL`.

### 23.5 Production-like runner seam

Shell contract tests verify the top-level runner:

- executes the planner's ordered decisions;
- preserves existing cleanup ownership;
- materializes reused evidence into the run directory;
- creates a fresh namespace for a new full run;
- preserves `--resume` as same-run continuation;
- writes complete plan, phase, timing, and provenance evidence.

## 24. Acceptance scenarios

The implementation is not complete until all scenarios below are automated.

### A. Identical second run

Given one successful full run and unchanged effective inputs:

- source preflight executes fresh;
- reusable static evidence is accepted;
- unchanged images are not rebuilt;
- unchanged registry digests are revalidated rather than repushed;
- a new namespace is created;
- migrations, topics, Open Barriers, workloads, workload waits, Matching fleet
  verification, and retained provenance execute fresh;
- final status is `PASSED`.

### B. QuickFIX-only source change

When only effective QuickFIX image inputs change:

- QuickFIX image build evidence is invalidated;
- unrelated service and Matching image evidence remains reusable;
- complete image lock is reconstructed with the new QuickFIX digest;
- all required fresh Kubernetes runtime phases still execute;
- final status may be `PASSED`.

### C. Shared contract change

When a protobuf or shared project input used by multiple images changes:

- every affected image fingerprint changes;
- unaffected image fingerprints remain stable;
- downstream image lock and manifest evidence changes accordingly.

### D. Deployment-only change

When deployment manifest inputs change but application build inputs do not:

- application images remain reusable;
- affected static/render evidence is invalidated;
- fresh runtime deployment phases execute with the new manifests.

### E. Trading-day change

When the certification trading day changes:

- application images remain reusable unless their build inputs changed;
- Market Reference/session/render inputs that depend on trading day invalidate;
- the new namespace and all session/runtime phases execute fresh.

### F. Deleted registry artifact

When cached image evidence is valid but the referenced local-registry digest is
no longer addressable:

- planner must not report `REUSE` for publication;
- the verified source image is republished or rebuilt if required;
- new valid registry evidence is recorded before deployment.

### G. Corrupted cache object

When an evidence JSON object or cached file is corrupted:

- digest validation rejects it;
- phase executes normally;
- certification cannot pass by trusting the corrupt index entry.

### H. Recreated kind cluster

After the canonical kind cluster is recreated:

- reusable image build evidence may survive;
- registry-hosted immutable artifacts may be revalidated;
- node-local `kind-load` state is never assumed present;
- Kubernetes runtime phases execute fresh.

### I. Dirty source

When current source violates the existing clean-source certification contract:

- the run fails before cached evidence can make it appear valid.

### J. Explicit skip

When a required phase is omitted through an existing `--skip-*` mode:

- cached evidence does not silently upgrade the explicit skip;
- the report remains `PARTIAL` according to existing semantics.

### K. Cache removed after retained run

After a production-like run passes with reused phases and retains its namespace:

- deleting the reusable cache must not make its dependent certification lose
  required run provenance or immutable image identity;
- dependent certification continues to use the retained run evidence directory,
  not implicit cache state.

## 25. Migration plan

Implementation should proceed as vertical slices. Do not rewrite the entire
runner before any reusable behavior is proven.

### Slice 1 — Phase result and timing schema

- record explicit per-phase result JSON;
- record durations;
- generate `plan.json` in an execution-only mode;
- preserve existing execution behavior.

No cache reuse is enabled in this slice.

### Slice 2 — Evidence store plus one static phase

- implement immutable evidence objects and index validation;
- make one cheap static phase content-addressable;
- prove corruption, miss, hit, and invalidation behavior;
- preserve all runtime phases as executed.

### Slice 3 — Per-image fingerprints and image reuse

- split image build into one logical phase per inventory entry;
- implement conservative build-input closures;
- record OCI digest outputs;
- reuse unchanged images by verified digest.

This is expected to produce the largest early reduction in repeated run time.

### Slice 4 — Incremental registry publication and complete lock

- revalidate existing registry digests;
- publish only missing/changed image artifacts;
- construct one complete immutable image lock for deployment;
- prove missing registry content causes republish, not stale reuse.

### Slice 5 — Full Phase DAG planner

- move ordering and reuse decisions into `PhaseGraph` and
  `CertificationPlanner`;
- remove duplicated cache conditions from individual phase callers;
- keep current shell modules as execution adapters where appropriate.

### Slice 6 — Evaluate environment reuse

Only after timing data exists, determine whether Compose/Kafka fault evidence
is still a dominant cost. If it is, write a separate specification for the
necessary environment identity and `REVALIDATE` contract before changing those
phases away from `FRESH`.

### Slice 7 — Optional bounded parallel scheduling

Only after correctness and reuse behavior are stable, use DAG independence to
run resource-compatible phases concurrently. Parallelism must not be required
for the content-addressed design to provide value.

## 26. Implementation constraints

- Reuse decisions belong in the planner, not in each shell call site.
- Existing Kubernetes lifecycle helpers remain the only namespace ownership and
  cleanup implementation.
- Existing canonical image inventory remains the source of which images belong
  to a full local deployment.
- Existing registry image-lock validation remains part of the output contract;
  it should be deepened rather than replaced by a second lock format.
- Current source/provenance checks remain fail-closed.
- A retained run must expose the same or stronger provenance than the current
  workflow even when some prerequisite evidence was reused.
- Reused evidence must remain understandable to a new maintainer from the run
  report without reading cache implementation internals.
- Implementation should prefer a few deep modules over phase-specific cache
  helper functions spread across the runner.

## 27. Review checklist

Before implementation is accepted, review the resulting diff for:

- **Duplicated Code** — fingerprint/evidence validation is implemented once;
- **Shotgun Surgery** — adding a phase should normally change the graph
  definition and its adapter, not many cache call sites;
- **Divergent Change** — execution, fingerprinting, evidence storage, and
  planning remain separate responsibilities;
- **Primitive Obsession** — phase IDs, policies, evidence identities, and
  decisions use validated representations rather than unconstrained strings
  passed across many callers;
- **Repeated Switches** — policy behavior is centralized;
- **Speculative Generality** — no remote cache, distributed scheduler, or
  environment reuse is implemented before a demonstrated requirement;
- **Middle Man** — modules must hide actual graph, fingerprint, or evidence
  complexity rather than simply delegate to the old runner;
- **Mysterious Name** — terms in logs and evidence use the vocabulary defined in
  this specification.

## 28. Definition of done

The Phase DAG and content-addressed evidence architecture is complete when:

1. a full cold run still proves every requirement that the current full runner
   proves;
2. an unchanged warm run produces `PASSED` while avoiding re-execution of valid
   reusable phases;
3. every Kubernetes runtime phase identified as `FRESH` executes in every new
   full run;
4. cache corruption, missing artifacts, fingerprint changes, and output
   identity mismatches cannot produce a false-positive PASS;
5. dependent certification can verify a retained run without relying on the
   reusable cache directory;
6. phase plan and result evidence explain every execute/reuse/revalidate
   decision;
7. timing evidence demonstrates that reusable unchanged phases no longer
   dominate warm-run wall-clock time;
8. focused contract tests and the complete relevant local lifecycle CI pass;
9. documentation describes cache cleanup and retained-run lifecycle without
   requiring operators to know internal helper functions;
10. the implementation passes the repository's final code-smell review and
    preserves the normal single production-like certification entry point.
