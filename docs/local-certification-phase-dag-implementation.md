# Local Certification Phase DAG Implementation

Status: Implemented; acceptance requires exact-head CI and the completion gates
listed below.

This is the implementation specification for Issue #185 and
`docs/local-certification-phase-dag.md`. The architecture specification explains
why Phase DAG execution and content-addressed evidence are used. This document
explains the current shell interfaces, ownership of behavior, failure semantics,
and verification seams.

The normal operator entry point remains:

```bash
scripts/run-local-production-like-certification.sh --keep-resources
```

There is no separate fast certification pipeline. Verified reuse is part of the
normal full runner.

## 1. Module ownership

The certification workflow concentrates policy in four modules and keeps
external state behind adapters.

### PhaseGraph

File: `scripts/lib/local-certification-phase-graph.sh`

Owns:

- phase identity;
- dependency edges;
- active-profile selection;
- reuse policy;
- same-run resume policy;
- declared input/output kinds;
- definition versions;
- topological execution order.

Public interface:

```text
certification_phase_ids
certification_phase_policy PHASE_ID
certification_phase_resume_mode PHASE_ID
certification_phase_dependencies PHASE_ID
certification_phase_definition_version PHASE_ID
certification_phase_input_kinds PHASE_ID
certification_phase_output_kinds PHASE_ID
certification_selected_image_services
certification_required_phase_ids
certification_explicit_skip_entries
certification_phase_validate_graph
```

Every phase declares exactly one reuse policy:

```text
FRESH
CONTENT_ADDRESSED
REVALIDATE
```

Every phase also declares one same-run resume mode:

```text
REEXECUTE
REUSE_RESULT
VALIDATE
FORBID
```

The graph rejects duplicate or invalid phase identifiers, invalid definition
versions, unknown dependencies, and dependency cycles.

`certification_required_phase_ids` starts from the active profile roots, walks
the dependency closure, and emits a topological sequence. Registration order is
not execution order. Callers do not maintain a second ordered phase list.

### PhaseFingerprint

File: `scripts/lib/local-certification-fingerprint.sh`

Owns deterministic effective-input identity.

Public interface:

```text
certification_phase_input_manifest PHASE_ID [PHASE_ARGUMENTS...]
certification_phase_fingerprint PHASE_ID [PHASE_ARGUMENTS...]
certification_image_input_manifest SERVICE
certification_image_input_fingerprint SERVICE
```

The canonical input manifest is the explainable source of truth. A fingerprint
is:

```text
SHA-256(canonical input manifest)
```

Manifests use repository-relative paths, file contents, executable mode when
relevant, normalized scalar configuration, toolchain identities, upstream
immutable artifact identities, and implementation files that can change the
phase output or its evidence interpretation. Absolute workspace paths and mtimes
are not effective inputs.

### EvidenceStore

File: `scripts/lib/local-certification-evidence.sh`

Owns immutable cross-run PASS evidence.

Public interface:

```text
certification_evidence_probe PHASE_ID INPUT_FINGERPRINT
certification_evidence_find_valid PHASE_ID INPUT_FINGERPRINT
certification_evidence_publish PHASE_ID INPUT_FINGERPRINT RESULT_FILE
certification_evidence_materialize EVIDENCE_DIGEST DESTINATION
certification_evidence_output_identity EVIDENCE_DIGEST KIND NAME
certification_evidence_output_location EVIDENCE_DIGEST KIND NAME
```

The reusable cache defaults to:

```text
out/certification-cache/
```

and may be overridden by `SIMPLEMATCH_CERTIFICATION_CACHE_DIR`.

The cache is not a trust root. Candidate evidence is accepted only after current
validation of object digest, schema, phase identity, definition version, exact
input fingerprint, PASS status, and declared output identities.

`certification_evidence_probe` reports:

```text
HIT
MISS
REJECTED
```

`MISS` means no candidate exists. `REJECTED` means a candidate or index existed
but could not be trusted. The planner preserves this distinction in run evidence.

### CertificationPlanner

File: `scripts/lib/local-certification-planner.sh`

Owns execution/reuse decisions, graph traversal, planning diagnostics, and
current-run evidence completeness.

Public interface:

```text
certification_plan_initialize RUN_EVIDENCE_DIR
certification_plan_execute DISPATCHER
certification_plan_phase PHASE_ID [PHASE_ARGUMENTS...]
certification_plan_record_execution PHASE INPUT REASON EXECUTION_JSON [COMMAND...]
certification_plan_record_failure PHASE INPUT REASON EXECUTION_JSON
certification_plan_record_reuse PHASE DECISION INPUT EVIDENCE REASON EXECUTION_JSON
certification_phase_resume_result_valid PHASE_ID
certification_phase_resume_decision PHASE_ID [REQUIRED_OUTPUT]
certification_plan_finalize
```

For an active phase, planning returns:

```text
DECISION|INPUT_FINGERPRINT|EVIDENCE_DIGEST|REASON
```

where `DECISION` is `EXECUTE`, `REUSE`, or `REVALIDATE`.

`FRESH` always executes in a new production-like run. `CONTENT_ADDRESSED` reuses
only exact evidence whose outputs still validate. `REVALIDATE` accepts prior
evidence only after its current external check succeeds.

Every plan entry records cache lookup and revalidation time separately from
command execution duration:

```text
lookupDurationMillis
revalidationDurationMillis
```

Cache rejection reasons remain specific instead of being reduced to a generic
cache miss.

## 2. Adapter seams

Policy modules do not know Docker, registry, Kafka, or Kubernetes command
syntax.

### Artifact adapter

File: `scripts/lib/local-certification-artifacts.sh`

This is the small seam used by `CertificationPlanner` for reusable output
behavior. It dispatches to concrete image or Kafka adapters.

Interface:

```text
certification_phase_cached_outputs_valid
certification_phase_current_outputs_valid
certification_phase_revalidate
certification_phase_outputs_json
certification_phase_materialize_reused_outputs
```

Because this seam can change evidence capture, validation, and materialization,
its implementation is part of the effective input closure for affected reusable
phases.

### Image adapter

File: `scripts/lib/local-certification-images.sh`

Owns Docker image output identity, registry digest validation, image-lock
materialization, and complete lock construction.

Each canonical image has one content-addressed phase:

```text
local-image-build/<service>
```

A cached local image is reusable only when both the requested image location and
immutable Docker image ID match prior evidence.

Registry publication uses:

```text
registry-publish/<service>
```

and is `REVALIDATE`. The exact digest-qualified registry reference must still be
addressable. A missing digest causes execution rather than stale reuse.

`registry-image-lock` reconstructs one complete canonical `local-images.lock`
from validated per-service fragments. Deployment rendering continues to consume
the existing lock format.

### Kafka adapter

File: `scripts/lib/local-certification-kafka.sh`

The reusable producer contract treats the generated producer configuration as a
real content-addressed file artifact. Evidence contains the file identity and
content needed to materialize it into the current run. Same-run validation
rejects a missing or changed producer configuration.

### Execution adapter

Files:

```text
scripts/lib/local-certification-framework.sh
scripts/lib/local-certification-run.sh
```

`run_logged` and `run_capture` remain separate because their output contracts
are different: one writes a command log, while the other treats captured stdout
as the phase artifact.

Their shared lifecycle is concentrated in one internal phase context that owns:

- resume evaluation;
- planner decision retrieval;
- start/completion timing;
- structured execution metadata;
- result recording;
- completion markers.

`local-certification-run.sh` maps phase identifiers to concrete execution
adapters. It does not select execution order. `CertificationPlanner` iterates the
PhaseGraph sequence and calls that dispatcher.

## 3. Image input identity

Spring application image fingerprints include:

- the owning service source;
- shared Java and protobuf inputs;
- Gradle build logic and configuration;
- the Gradle wrapper;
- local image build/inventory logic;
- concrete and generic artifact-adapter implementations;
- immutable buildpack builder and run-image identities.

Builder and run-image resolution follows the effective `BootBuildImage` pull
policy used by `scripts/build-local-images.sh`:

- `ALWAYS` resolves the registry identity;
- `IF_NOT_PRESENT` uses a local identity when available, otherwise registry;
- `NEVER` requires a local identity.

Without an override, the effective policy is `ALWAYS`. Therefore a mutable
upstream builder tag changing in the registry invalidates prior image evidence
even when an older image with the same tag remains locally.

Dockerfile image fingerprints conservatively include the effective repository
build context, Dockerfile/build inputs, artifact-adapter implementation, and
remotely resolved immutable base-image identities.

Registry publication and image-lock fingerprints include the implementation
files responsible for publication, transport, output validation, and
materialization. Kafka producer evidence likewise includes the Kafka adapter and
generic artifact seam. An implementation-only change therefore cannot silently
accept evidence created under older output semantics.

## 4. Explicit SKIP

Operator-selected omission is not reuse.

`certification_explicit_skip_entries` derives omitted phases from the PhaseGraph
by cumulatively restoring:

```text
--skip-build
--skip-compose
--skip-kubernetes
--matching-fleet-only
```

The planner records each omitted requirement as `SKIP` with no input fingerprint,
evidence digest, or PASS result. Any explicit skip/profile restriction keeps the
human-readable certification result `PARTIAL`.

## 5. Same-run resume

`--resume` continues one retained run. It does not enable cross-run cache reuse.
The run context includes:

```text
run_id
namespace
cluster
trading_day
image_tag
image_transport
source_signature
skip_build
skip_compose
skip_kubernetes
matching_fleet_only
```

Resume restores the original `run_id`; a new ID is not generated for the same
run.

A completion marker is only a resume candidate. PhaseGraph resume policy then
decides what is safe:

- `REUSE_RESULT` accepts a current-run PASS only when its definition and
  run-local outputs still validate;
- `REEXECUTE` runs the current check again;
- `VALIDATE` requires an explicit current-state validator;
- `FORBID` rejects continuation where the available evidence cannot prove a
  side effect safe to replay or accept.

The retained Kubernetes namespace validator checks repository-owned disposable
identity and the original `run_id`. Kubernetes input continuation validates the
immutable Market Reference and FIX dictionary ConfigMaps, session/trading-day
identity, Matching image digest, and required local secrets.

### Non-replayable side effects

A `FORBID` phase writes a run-local `.started` marker before executing its side
effect. The marker is removed only after successful current-run evidence and the
normal completion marker are durable.

If the process dies in that interval, later `--resume` cannot know whether the
side effect occurred and fails closed. `kubernetes-open-barriers` uses this
policy so an ambiguous interruption cannot publish a second Open Barrier.

## 6. Fresh runtime proof

The following remain runtime-state-dependent and are not reused across runs:

- source preflight;
- Compose startup, readiness, status, and teardown;
- Kafka capacity, topic/runtime inspection, and broker-failure observation;
- registry connectivity;
- kind node image import;
- namespace and runtime inputs;
- migrations and topic provisioning;
- Open Barriers;
- workload deployment/readiness;
- Matching fleet verification;
- retained-run provenance.

The reusable cache does not contain PostgreSQL data, Kafka offsets, Gateway
state, Matching ownership, critical-consumer progress, or namespace state.

## 7. Current-run evidence

Each active phase writes:

```text
<run-evidence>/phases/<phase-id>/result.json
```

Current-run decisions are:

```text
EXECUTED
REUSED
REVALIDATED
```

Result recording receives one execution metadata object:

```json
{
  "startedAtUtc": "...",
  "completedAtUtc": "...",
  "durationMillis": 123
}
```

The result also records planning timing, source revision, effective input
fingerprint, reusable evidence digest where applicable, decision reason, and
output identities.

`plan.json` records active planning decisions plus explicit `SKIP` entries.
`evidence-manifest.json` contains the validated active required phase results and
run-relative result paths. Reused source evidence and reusable outputs are
materialized into the current run.

Deleting the reusable cache after a retained PASS therefore does not remove the
run-local evidence required by dependent certification.

## 8. Failure semantics

The workflow fails closed when, among other cases:

- PhaseGraph validation or dependency production fails;
- an effective input cannot be read or canonicalized;
- a mutable upstream image cannot be resolved according to effective pull
  policy;
- a phase command fails;
- structured execution metadata is malformed;
- successful execution cannot produce valid current-run evidence;
- reusable evidence cannot be published atomically;
- cached outputs no longer match their immutable identity or location;
- current registry revalidation fails and fresh publication also fails;
- complete image-lock construction is incomplete or invalid;
- an active required phase is missing, failed, duplicated, unknown, or uses an
  old definition version;
- retained runtime state fails its resume validator;
- a non-replayable phase has an ambiguous started-but-not-completed marker;
- final retained evidence cannot be written atomically.

A rejected cache object is not a PASS. The planner records why it was rejected
and executes the requirement normally. If that execution fails, the run fails.

## 9. Verification seams

Focused contracts exercise behavior through the same seams used by the runner:

- `test-local-certification-incremental.sh` — graph policy/profile selection,
  deterministic fingerprints, narrow/shared invalidation, cache integrity,
  registry revalidation, cold execution, warm reuse, and result completeness;
- `test-local-certification-reuse-safety.sh` — producer failure propagation,
  image location/identity, deployment-only independence, and retained-run
  operation after cache deletion;
- `test-local-certification-skip-semantics.sh` — explicit SKIP representation
  and proof-profile resume identity;
- `test-local-certification-review-hardening.sh` — topological ordering,
  declarative metadata, resume modes, pull-policy identity, cache diagnostics,
  validation timing, and producer output materialization;
- `test-local-certification-output-lineage.sh` — run-local producer output
  validation and materialization;
- `test-local-certification-artifact-fingerprint.sh` — generic artifact-adapter
  changes invalidate image, registry, and Kafka reusable evidence;
- `test-local-production-like.sh` — broad orchestration behavior using
  PhaseGraph rather than source-line ordering.

Local Resource Lifecycle CI executes the focused contracts and a live kind
registry/resource lifecycle smoke test. Other workflows triggered by the final
tree must also pass.

## 10. Acceptance mapping

| Issue #185 requirement | Verification |
| --- | --- |
| Cold full run retains existing proof | Same operator runner; FRESH runtime policy; broad contracts/live smoke |
| Warm unchanged work is reused safely | Planner cold/warm contract |
| FRESH remains fresh across runs | PhaseGraph/Planner contracts |
| QuickFIX-only change is narrow | Per-service image fingerprint contract |
| Shared input invalidates affected images | Shared Spring-input contract |
| Trading-day/deployment-only change avoids unrelated rebuilds | Fingerprint contracts |
| Mutable builder/run image changes invalidate proof | Pull-policy identity contract |
| Publication implementation changes invalidate proof | Implementation-sensitive fingerprint contracts |
| Artifact dispatch changes invalidate proof | Artifact-adapter fingerprint contract |
| Missing registry digest is not stale reuse | Registry revalidation contract |
| Corrupt/wrong cache cannot false-PASS | Evidence probe and definition-version contracts |
| Explicit SKIP differs from reuse | SKIP contract and PARTIAL report semantics |
| Resume is same-run and fail closed | Run identity, resume mode, current-state, and crash-window contracts |
| Retained PASS survives cache deletion | Reuse-safety retained-evidence contract |
| Phase ordering has one source of truth | Topological PhaseGraph + planner dispatcher contract |
| Decisions and timing are explainable | Plan/result/report/evidence-manifest contracts |

## 11. Deferred work

Still out of scope:

- remote or distributed reusable cache;
- reuse of database, Kafka, Gateway, Matching ownership, or critical-consumer
  runtime state;
- environment-fault evidence reuse without a separately specified environment
  identity and revalidation rule;
- unbounded parallel image builds or Kubernetes mutations;
- a second certification pipeline.

Bounded parallel scheduling should be considered only if measured cold/warm runs
show remaining independent phases dominate wall-clock time.

## 12. Completion gate

Repository implementation is accepted only when the exact final branch head has:

1. architecture and implementation specifications aligned with code;
2. focused incremental/reuse/resume/artifact contracts passing;
3. Local Resource Lifecycle CI passing, including live kind smoke;
4. every additional workflow triggered by the final tree passing;
5. a final diff review with no unresolved Critical, High, or Medium Standards or
   Spec finding;
6. milestone-oriented commit history whose messages follow repository
   conventions.

Actual workstation cold/warm wall-clock measurements are operational evidence.
CI contracts do not fabricate those measurements.