# Local Certification Phase DAG Implementation Plan

Status: Approved for implementation by Issue #185

This document translates `docs/local-certification-phase-dag.md` into concrete
repository changes. It is intentionally narrower than the architecture
specification: it defines which shell modules own each responsibility, how the
current runner calls them, which phase identifiers are stable, how evidence is
serialized, and which tests must protect every milestone.

The implementation changes local certification infrastructure only. It does not
change trading-domain terminology, application behavior, or Kubernetes
ownership rules.

## 1. Current implementation state

At the start of Issue #185 implementation:

- `run-local-production-like-certification.sh` remains the single operator entry
  point;
- `local-certification-bootstrap.sh` performs static checks and one monolithic
  local image build;
- `local-certification-run.sh` executes Compose verification, image transport,
  Kubernetes deployment, and live fleet verification in fixed shell order;
- `local-certification-framework.sh` provides `run_logged` and
  `run_capture`, but stores only log files and same-run phase markers;
- `--resume` reuses successful phases only inside the same evidence directory
  and retained namespace;
- the registry image lock already records digest-qualified image references;
- `build-local-images.sh` and `publish-local-images.sh` already accept repeated
  or individual `--service` selection;
- no cross-run evidence store, per-phase content fingerprint, per-image build
  cache decision, or Phase DAG planner exists.

The implementation therefore deepens existing seams instead of adding a second
certification pipeline.

## 2. Implementation modules

Four policy modules are added under `scripts/lib/`. They are sourced by the
existing top-level runner and have no independent command-line entry point.

### 2.1 `local-certification-phase-graph.sh`

Owns phase definitions only.

Public shell interface:

```text
certification_phase_ids
certification_phase_policy PHASE_ID
certification_phase_dependencies PHASE_ID
certification_phase_definition_version PHASE_ID
certification_phase_validate_graph
```

The graph module must not read the evidence cache, execute commands, calculate
hashes, or mutate Docker/Kubernetes state.

Phase policy values are exactly:

```text
FRESH
CONTENT_ADDRESSED
REVALIDATE
```

Unknown phase identifiers are errors. There is no implicit policy.

### 2.2 `local-certification-fingerprint.sh`

Owns deterministic effective-input identity.

Public shell interface:

```text
certification_phase_fingerprint PHASE_ID [PHASE_ARGUMENTS...]
certification_image_input_fingerprint SERVICE
```

Both functions print one `sha256:<64 lowercase hex>` identity on success.

The module hides canonical path collection, file mode/content hashing, scalar
configuration normalization, toolchain identity, and upstream artifact identity.
It must not execute certification phases or modify the evidence store.

Initial file collection is deliberately conservative. Over-invalidation is
acceptable; under-invalidation is not.

#### Spring image closure

For a Spring image, the initial fingerprint includes:

- the owning `services/<service>` directory;
- `shared-java/**` and `proto/**`;
- `build-logic/**`;
- `gradle/libs.versions.toml`, dependency verification/lock files when present,
  `settings.gradle.kts`, root Gradle build files, and the Gradle wrapper;
- `scripts/build-local-images.sh` and the canonical image inventory module;
- explicit local Boot builder/run-image configuration values;
- the resolved immutable Docker identities for explicitly configured builder or
  run images when available.

A source-only change under another service directory must not invalidate this
fingerprint.

#### Dockerfile image closure

The current Flyway, verifier, and Matching Dockerfiles use `COPY .`, so their
first implementation fingerprints the repository Docker build context after
`.dockerignore` exclusions, together with:

- the Dockerfile content and executable mode;
- declared build arguments that affect output;
- resolved immutable base-image identities when Docker can resolve them.

This is conservative but matches the actual broad Docker build context. A later
optimization may narrow these Docker contexts only together with Dockerfile
changes and invalidation tests.

### 2.3 `local-certification-evidence.sh`

Owns cross-run reusable evidence storage.

Public shell interface:

```text
certification_evidence_find_valid PHASE_ID INPUT_FINGERPRINT
certification_evidence_publish PHASE_ID INPUT_FINGERPRINT RESULT_FILE
certification_evidence_materialize EVIDENCE_DIGEST RESULT_PATH
certification_evidence_output_identity EVIDENCE_DIGEST KIND NAME
```

The default store is:

```text
out/certification-cache/
```

and may be overridden through
`SIMPLEMATCH_CERTIFICATION_CACHE_DIR`.

The store validates its own objects before returning a hit. Index files are
lookup hints, never trust roots.

Evidence publication is atomic:

1. write canonical result JSON to a temporary object;
2. calculate the evidence digest;
3. atomically install the immutable object if absent;
4. validate the installed object;
5. atomically update the `(phase,input)` index reference.

Only PASS evidence may be indexed.

A malformed index, missing object, corrupt object, wrong phase, wrong input
fingerprint, unsupported schema version, or invalid output identity returns a
cache miss to the planner. It does not manufacture a PASS.

### 2.4 `local-certification-planner.sh`

Owns execution/reuse decisions and per-run plan/result recording.

Public shell interface:

```text
certification_plan_initialize RUN_EVIDENCE_DIR
certification_plan_phase PHASE_ID [PHASE_ARGUMENTS...]
certification_plan_record_execution PHASE_ID INPUT_FINGERPRINT RESULT_FILE
certification_plan_finalize
```

`certification_plan_phase` returns a decision through stdout in the form:

```text
DECISION|INPUT_FINGERPRINT|EVIDENCE_DIGEST|REASON
```

where `DECISION` is exactly:

```text
EXECUTE
REUSE
REVALIDATE
```

The planner never chooses `REUSE` for a `FRESH` phase.

The planner is the only module allowed to translate phase policy plus evidence
state into an execution decision. Call sites must not reproduce cache rules.

## 3. Execution adapter seam

`local-certification-framework.sh` remains the execution adapter. `run_logged`
and `run_capture` are deepened rather than replaced.

For each phase they must:

1. ask the planner for a decision;
2. record start time;
3. execute the command only for `EXECUTE`;
4. perform the phase-specific current check for `REVALIDATE`;
5. materialize prior evidence for `REUSE` or successful `REVALIDATE`;
6. record end time and elapsed milliseconds;
7. write one current-run result JSON;
8. update the human-readable completed-phase summary.

Existing same-run `--resume` markers remain separate. Same-run resume is checked
before cross-run planning only for phases whose runtime state can still be
validated by the existing resume contract.

The framework must continue to execute an `EXECUTE` command exactly once.

## 4. Stable phase identifiers

The first implementation uses these phase identifiers.

### 4.1 Static and configuration phases

```text
static-kubernetes-overlays
static-kubernetes-dependencies
static-matching-manifests
static-matching-profile
static-flyway-services
compose-config
local-image-inventory
kafka-producer-contract
```

These are initially `CONTENT_ADDRESSED`, except source cleanliness/preflight
which remains outside reusable phase execution and is checked fresh.

### 4.2 Image phases

One logical build phase exists per canonical inventory service:

```text
local-image-build/account-service
local-image-build/risk-service
local-image-build/persistence
local-image-build/market-data-projection
local-image-build/marketdata-publisher
local-image-build/marketdata-streamer
local-image-build/query-service
local-image-build/quickfix-gateway
local-image-build/flyway-runner
local-image-build/risk-matching-e2e-verifier
local-image-build/matching
```

Each is `CONTENT_ADDRESSED`.

Registry publication is likewise per service:

```text
registry-publish/<service>
```

Each is `REVALIDATE`.

The aggregate complete image lock is:

```text
registry-image-lock
```

and is `CONTENT_ADDRESSED` over the ordered canonical set of immutable registry
references.

### 4.3 Fresh environment and runtime phases

The following remain `FRESH`:

```text
compose-up
compose-wait
compose-status
kafka-capacity-evidence
kafka-topic-fixture
kafka-broker-failure-live
compose-down-before-kubernetes
registry-connectivity
kind-load-import
kubernetes-namespace
kubernetes-inputs
kubernetes-platform-apply
kubernetes-migrations
kubernetes-topic-provisioning
kubernetes-open-barriers
kubernetes-workload-apply
kubernetes-risk-outbox-connector
kubernetes-workloads
kubernetes-fleet
retained-run-provenance
```

`matching-fleet-only` may select a smaller graph profile, but selected runtime
phases keep the same policy.

## 5. Phase result schema

Every phase used by the current run writes:

```text
<run-evidence>/phases/<phase-id>/result.json
```

with schema version 1:

```json
{
  "schemaVersion": 1,
  "phaseId": "static-kubernetes-overlays",
  "definitionVersion": 1,
  "decision": "EXECUTED",
  "status": "PASS",
  "inputFingerprint": "sha256:...",
  "evidenceDigest": "sha256:...",
  "reason": "cache miss",
  "execution": {
    "sourceRevision": "<git-sha>",
    "startedAtUtc": "2026-08-28T00:00:00Z",
    "completedAtUtc": "2026-08-28T00:00:01Z",
    "durationMillis": 1000
  },
  "outputs": []
}
```

Current-run `decision` values are:

```text
EXECUTED
REUSED
REVALIDATED
SKIPPED
```

Reusable cache objects store PASS results only. Failure results remain current
run evidence and are never indexed.

## 6. Plan schema

`<run-evidence>/plan.json` contains schema version 1 and an ordered phase array.
Each entry records:

```json
{
  "phaseId": "local-image-build/quickfix-gateway",
  "policy": "CONTENT_ADDRESSED",
  "decision": "EXECUTE",
  "inputFingerprint": "sha256:...",
  "evidenceDigest": null,
  "reason": "no valid evidence"
}
```

The planner may append entries as upstream identities become known. Before a
full run reports PASS, every selected required phase must have exactly one plan
entry and one successful run result.

## 7. Static phase fingerprints

Static validators use explicit repository input manifests owned by the
fingerprint module. The implementation must not derive dependencies by grepping
commands at runtime.

Initial mappings:

- `static-kubernetes-overlays`: validator plus `deploy/k8s/**`;
- `static-kubernetes-dependencies`: validator, local deployment manifests,
  image inventory, and local image transport policy;
- `static-matching-manifests`: validator plus Matching Kubernetes manifests;
- `static-matching-profile`: validator plus Matching topic profile inputs;
- `static-flyway-services`: validator, Flyway build logic, Flyway configuration,
  and migration resources;
- `compose-config`: production-like Compose file plus interpolation inputs;
- `local-image-inventory`: inventory module plus build-image script;
- `kafka-producer-contract`: producer validator plus producer/profile
  configuration inputs.

The mapping is intentionally centralized in the fingerprint module so adding a
new caller does not duplicate invalidation knowledge.

## 8. Image build evidence

A successful image build phase records one output:

```json
{
  "kind": "docker-image",
  "name": "quickfix-gateway",
  "identity": "sha256:<docker-image-id>",
  "location": "quickfix-gateway:<tag>"
}
```

A build evidence hit is accepted only if `docker image inspect` proves that the
current source image location still resolves to the recorded immutable image
ID. If it does not, the build phase executes.

This first implementation does not pull a missing source image back from the
registry merely to satisfy build evidence. That optimization can be added later
without changing the planner interface.

## 9. Registry publication evidence

A successful publication phase records:

```json
{
  "kind": "registry-image",
  "name": "quickfix-gateway",
  "identity": "sha256:<manifest-digest>",
  "location": "localhost:5001/quickfix-gateway@sha256:..."
}
```

For `REVALIDATE`, the current local registry is queried for the exact digest.
If the digest remains addressable, no push is performed and the result is
`REVALIDATED`.

If the digest is absent, publication executes for that service only by using the
existing `publish-local-images.sh --service ...` adapter. The resulting partial
lock entry is validated before it can enter evidence.

## 10. Complete image lock construction

The planner/execution adapter writes one partial lock fragment per selected
registry publication phase. `registry-image-lock` then constructs a complete
lock in canonical inventory order.

The constructor must:

- require exactly one selected fragment per required service;
- reject duplicate services;
- use existing `simplematch_local_image_lock_validate_file` semantics;
- require `simplematch_local_image_lock_render_profile` to resolve the expected
  full or matching-only profile;
- install `local-images.lock` atomically.

Deployment rendering continues to consume the existing lock format. No second
image-lock contract is introduced.

## 11. Fresh runtime rule

No cache decision may suppress execution of fresh runtime phases. In
particular, every new full run still creates and owns a new namespace and
executes migrations, topics, Open Barriers, workload deployment/wait, Matching
fleet verification, and retained provenance.

This rule is enforced twice:

1. graph contract tests assert those phase policies are `FRESH`;
2. planner tests assert a cache entry cannot produce `REUSE` for any `FRESH`
   phase.

## 12. Source cleanliness and provenance

Current clean-source/provenance checks remain fail closed and are evaluated for
every run before reusable evidence can make the run appear valid.

Cross-run evidence may originate from another Git revision only when the
phase's exact effective input fingerprint is identical. The originating
revision remains audit metadata.

Retained-run provenance is written fresh and materializes the image lock and
phase evidence needed by dependent certification. Dependent certification must
not read `out/certification-cache`.

## 13. Same-run resume

`--resume` continues to mean same-run continuation.

A resume requires the existing run context to match namespace, cluster, trading
day, image tag, image transport, and source signature. Cross-run cache lookup
requires no `--resume` flag.

Existing phase markers may remain during migration, but they must not become a
second independent cross-run cache implementation. Once planner result evidence
fully covers same-run reusable non-runtime phases, redundant marker logic may be
removed in a separate cleanup only after tests prove equivalent behavior.

## 14. Failure behavior

The implementation fails closed when:

- graph validation fails;
- required fingerprint inputs cannot be read;
- a phase command fails;
- a successful command cannot produce valid result evidence;
- an executed reusable phase cannot atomically publish valid PASS evidence;
- image output identity cannot be read;
- a newly published registry fragment cannot be validated;
- full lock construction is incomplete;
- current-run result or plan evidence is malformed.

A corrupt or stale reusable cache object is a cache miss, not a PASS and not by
itself a run failure. The phase executes normally unless execution also fails.

## 15. Shell maintainability rules

The implementation follows these constraints:

- top-level runner remains orchestration-only and below the existing line-count
  contract;
- policy tables live in `local-certification-phase-graph.sh` only;
- fingerprint dependency lists live in
  `local-certification-fingerprint.sh` only;
- evidence JSON/object/index rules live in
  `local-certification-evidence.sh` only;
- planner decisions and plan/result materialization live in
  `local-certification-planner.sh` only;
- Docker/registry mechanics remain adapters in existing image scripts or a
  narrowly scoped certification image adapter;
- functions accept the smallest practical parameter set and rely on documented
  runner-owned shared state only where existing sourced-module conventions make
  that clearer than long parameter lists;
- every non-trivial failure path uses explicit status propagation rather than
  relying on Bash `errexit` behavior inside conditional call contexts;
- JSON is written with `jq`, not hand-escaped string concatenation;
- temporary files are created next to their atomic destination when rename
  atomicity is required;
- phase identifiers are validated before they become filesystem paths.

## 16. Test seams

The architecture specification already establishes the test seams, so no new
internal test-only interfaces are introduced.

### Graph contract

A shell contract verifies:

- graph validation succeeds;
- phase IDs are unique and filesystem-safe;
- every phase has one explicit policy;
- all required fresh runtime phases remain `FRESH`;
- graph dependencies reference known phases and are acyclic.

### Fingerprint contract

Temporary fixture repositories/paths verify:

- identical content yields identical fingerprints;
- mtime and absolute parent path do not change identity;
- a declared file content change changes identity;
- QuickFIX-only source change changes QuickFIX image identity without changing
  an unrelated service identity;
- shared inputs invalidate applicable Spring image identities;
- trading day does not invalidate application image build identity.

### Evidence store contract

Temporary directories verify:

- valid PASS evidence can be found and materialized;
- corrupt object, wrong index digest, wrong phase/input, or missing object is
  rejected;
- only PASS is indexed;
- publication is atomic from the caller's perspective.

### Planner contract

In-memory/filesystem fixtures verify:

- `FRESH` always returns `EXECUTE`;
- valid `CONTENT_ADDRESSED` evidence returns `REUSE`;
- invalid evidence returns `EXECUTE`;
- valid `REVALIDATE` evidence invokes its current validator and returns
  `REVALIDATE` only on success;
- dependency output identity changes invalidate downstream fingerprints;
- `SKIP` remains separate from reuse.

### Runner contract

`test-local-production-like.sh` continues to prove top-level modularity and is
extended to verify that the four policy modules are sourced, execution still
occurs exactly once on `EXECUTE`, and selected fresh phases cannot be reused.

A focused `test-local-certification-incremental.sh` owns content-addressed
behavioral tests so `test-local-production-like.sh` does not become a second
large implementation specification.

## 17. Delivery milestones

The implementation uses four milestone commits after the architecture
specification commit.

### Milestone 1 — Define implementation contract

This document only. No execution semantics change.

### Milestone 2 — Record phase evidence

Implements:

- PhaseGraph definitions;
- PhaseFingerprint for static phases;
- EvidenceStore;
- planner execution-only/content-addressed decision support;
- phase result/timing JSON and `plan.json`;
- one static phase reuse tracer bullet;
- focused contracts.

Runtime behavior remains fresh.

### Milestone 3 — Reuse immutable images

Implements:

- per-image build phases;
- per-image build fingerprints;
- Docker image output validation;
- incremental registry revalidation/publication;
- complete image-lock construction;
- QuickFIX-only/shared/deployment/trading-day invalidation contracts.

### Milestone 4 — Complete DAG execution

Implements:

- all current reusable/static phase policies through the planner;
- explicit graph dependencies for the full selected profile;
- run evidence completeness checks;
- documentation/report updates;
- removal of obsolete duplicated cache conditions if proven redundant.

Environment fault verification and Kubernetes runtime phases remain fresh.

## 18. Validation gates

Each implementation milestone runs the narrowest relevant shell contracts first.
The final head must pass at least:

```text
bash scripts/test-local-certification-incremental.sh
bash scripts/test-local-production-like.sh
bash scripts/test-local-image-transport.sh
bash scripts/test-local-certification-trading-day.sh
```

GitHub Actions must then pass the complete `Local Resource Lifecycle CI` for the
exact final commit. Any additional workflow triggered by changed shared files
must also complete successfully before the implementation is considered done.

## 19. Completion evidence

Issue #185 is complete only when the final PR demonstrates:

- one cold plan where every required phase executes;
- one warm-plan contract where reusable phases are selected without weakening
  fresh runtime policy;
- per-image invalidation behavior for QuickFIX-only and shared-input changes;
- corrupted evidence and missing registry content fail safe;
- a complete digest-pinned image lock is still produced;
- run results contain timing and decision evidence;
- retained-run provenance remains independent of the cache directory;
- exact-head Local Resource Lifecycle CI is green;
- final review finds no duplicated cache policy, hidden second pipeline, or
  runtime phase incorrectly made reusable.
