# Local Certification Phase DAG Implementation

Status: Implemented; exact final-head validation pending

This document is the implementation specification for Issue #185 and
`docs/local-certification-phase-dag.md`. It describes the current repository
behavior rather than the sequence in which the implementation was developed.

The change affects local certification infrastructure only. It does not change
trading-domain terminology, application behavior, or Kubernetes ownership
rules. `run-local-production-like-certification.sh` remains the normal operator
entry point; there is no second or fast certification pipeline.

## 1. Design

Repeated production-like certification may reuse proof only when the work is
independent of fresh runtime state and its effective inputs and immutable
outputs can still be verified.

The implementation places four responsibilities behind narrow shell interfaces:

1. `PhaseGraph` owns phase identity, dependencies, active-profile selection,
   definition versions, and reuse policy.
2. `PhaseFingerprint` owns deterministic effective-input identity.
3. `EvidenceStore` owns immutable cross-run reusable PASS evidence.
4. `CertificationPlanner` owns planning decisions and complete current-run
   evidence.

Docker and registry operations remain behind the certification image adapter.
Command execution, timing, same-run markers, and reporting remain behind the
certification execution adapter. The top-level runner coordinates these modules
without reproducing their policy.

## 2. PhaseGraph

File: `scripts/lib/local-certification-phase-graph.sh`

Public interface:

```text
certification_phase_ids
certification_phase_policy PHASE_ID
certification_phase_dependencies PHASE_ID
certification_phase_definition_version PHASE_ID
certification_selected_image_services
certification_required_phase_ids
certification_explicit_skip_entries
certification_phase_validate_graph
```

Every registered phase has exactly one policy:

```text
FRESH
CONTENT_ADDRESSED
REVALIDATE
```

The graph rejects unknown and filesystem-unsafe phase identifiers, duplicate
phase identifiers, invalid definition versions, unknown dependencies, and
cycles.

The active required set is derived from a small set of profile roots plus the
transitive dependency closure. The implementation does not maintain a second
complete runtime-phase list. This keeps dependency ordering and required-phase
completeness in one place.

Selection-producing functions distinguish a valid empty or partial selection
from an actual producer failure. Normal filtering therefore returns success
even when the final registered phase is not selected.

### Explicit omissions

`certification_explicit_skip_entries` compares the active profile with the work
that would be required as operator-selected restrictions are restored. It emits:

```text
PHASE_ID|REASON
```

for work deliberately omitted by:

```text
--skip-build
--skip-compose
--skip-kubernetes
--matching-fleet-only
```

Restoration is cumulative so interacting flags cannot hide an omitted phase.
Each full-profile requirement omitted by the active profile receives one
explainable SKIP reason.

## 3. PhaseFingerprint

File: `scripts/lib/local-certification-fingerprint.sh`

Public interface:

```text
certification_phase_fingerprint PHASE_ID [PHASE_ARGUMENTS...]
certification_image_input_fingerprint SERVICE
```

Successful calls return one `sha256:<64 lowercase hex>` value. Fingerprints use
repository-relative paths, file content, executable mode where relevant,
normalized scalar inputs, required toolchain identities, and upstream artifact
identities. Absolute workspace paths and mtimes do not affect identity.

### Spring application images

A Spring application image fingerprint includes:

- the owning `services/<service>` directory;
- `shared-java/**` and `proto/**`;
- build logic and Gradle configuration;
- the Gradle wrapper;
- local image build and inventory logic;
- immutable buildpack builder and run-image identities.

A source-only change in another application service does not invalidate the
image. Trading-day and Kubernetes deployment-only changes also do not invalidate
an unrelated Spring application image.

### Dockerfile images

Current Flyway, verifier, and Matching Dockerfiles use a broad Docker build
context. Their fingerprints therefore conservatively include the effective
repository Docker context, Dockerfile/build inputs, and immutable base-image
identities. This may over-invalidate, but it does not knowingly under-invalidate
relative to the actual build context.

## 4. EvidenceStore

File: `scripts/lib/local-certification-evidence.sh`

Public interface:

```text
certification_evidence_find_valid PHASE_ID INPUT_FINGERPRINT
certification_evidence_publish PHASE_ID INPUT_FINGERPRINT RESULT_FILE
certification_evidence_materialize EVIDENCE_DIGEST DESTINATION
certification_evidence_output_identity EVIDENCE_DIGEST KIND NAME
certification_evidence_output_location EVIDENCE_DIGEST KIND NAME
```

The default reusable store is:

```text
out/certification-cache/
```

and may be overridden with `SIMPLEMATCH_CERTIFICATION_CACHE_DIR`.

The cache is untrusted. A candidate object is reusable only after validating its
content digest, schema, phase identity, current definition version, exact input
fingerprint, PASS status, and required output identities.

Malformed indexes, missing objects, corrupt objects, old phase definitions,
wrong fingerprints, missing outputs, or output mismatches become cache misses.
They never manufacture a PASS.

Only PASS results enter the reusable store. Publication installs immutable
content-addressed evidence before atomically updating the `(phase,input)` lookup
reference.

## 5. CertificationPlanner

File: `scripts/lib/local-certification-planner.sh`

Public interface:

```text
certification_plan_initialize RUN_EVIDENCE_DIR
certification_plan_phase PHASE_ID [PHASE_ARGUMENTS...]
certification_plan_record_execution ...
certification_plan_record_failure ...
certification_plan_record_reuse ...
certification_phase_resume_result_valid PHASE_ID
certification_plan_finalize
```

For an active phase, `certification_plan_phase` returns:

```text
DECISION|INPUT_FINGERPRINT|EVIDENCE_DIGEST|REASON
```

where the decision is `EXECUTE`, `REUSE`, or `REVALIDATE`.

`FRESH` always resolves to `EXECUTE`. `CONTENT_ADDRESSED` resolves to `REUSE`
only when exact prior evidence and reusable outputs validate. `REVALIDATE`
resolves to `REVALIDATE` only when prior evidence exists and the current
external check succeeds.

### SKIP representation

During plan initialization the Planner records PhaseGraph omissions as plan
entries with:

```json
{
  "decision": "SKIP",
  "inputFingerprint": null,
  "evidenceDigest": null
}
```

A SKIP entry is explanation, not proof. It creates no phase result, no PASS
status, and no reusable evidence. The report retains `PARTIAL` status whenever
an operator-selected skip/profile restriction is active.

Verified `REUSE` is therefore semantically different from `SKIP`: reuse proves
the current requirement from validated immutable evidence, while skip records
that the requirement was deliberately not proven in this run.

### Finalization

Before a non-dry-run plan finalizes, every active required phase must have
exactly one plan entry and one current valid PASS result using the current phase
definition version.

After those checks succeed, the Planner atomically writes:

```text
<run-evidence>/evidence-manifest.json
```

The manifest contains the active required phase decisions, timing, input and
evidence identities, outputs, and run-relative `resultPath` values. It contains
no reusable-cache path.

## 6. Execution adapter

File: `scripts/lib/local-certification-framework.sh`

`run_logged` and `run_capture` remain the command-execution seam. They own
bounded execution, timing, current-run result creation, completion reporting,
and same-run markers.

`run_logged` can execute, reuse, or revalidate according to Planner output.
`run_capture` retains stricter output semantics because captured stdout is the
phase artifact; it does not silently reuse a phase without an output
materialization adapter.

The two functions intentionally remain distinct. Combining them behind mode
flags would enlarge the interface and obscure the different output contract.

Meaningful failures are propagated explicitly instead of relying on Bash
`errexit` inside conditional call contexts. Producer functions likewise return
nonzero only for actual failures, not because a normal selection condition was
false on their final iteration.

## 7. Image adapter

File: `scripts/lib/local-certification-images.sh`

Each canonical local image has one content-addressed build phase:

```text
local-image-build/<service>
```

Build reuse requires both the requested source-image location and the immutable
Docker image ID to match prior evidence. An identical image ID under another tag
is insufficient because later publication consumes the requested tag.

Each selected registry image has one revalidated publication phase:

```text
registry-publish/<service>
```

The exact digest-qualified registry reference must remain addressable. A missing
digest causes that service to publish again; it is never accepted as stale
reuse.

`registry-image-lock` reconstructs one complete canonical `local-images.lock`
from validated per-service fragments. Deployment rendering continues to consume
that existing lock format.

## 8. Fresh runtime policy

Runtime-state-dependent verification remains `FRESH`. This includes the source
preflight, Compose runtime, Kafka runtime state and fault observation,
registry-connectivity observation, kind import, namespace creation, Kubernetes
inputs/platform application, migrations, topic provisioning, Open Barriers,
workload application/readiness, Matching fleet verification, and retained-run
provenance.

Neither a full profile nor Matching-only profile changes a selected runtime
phase from `FRESH` to reusable.

The reusable cache does not contain PostgreSQL data, Kafka offsets, Gateway
state, Matching ownership, critical-consumer progress, or namespace state.

## 9. Current-run evidence

Each active planned phase writes:

```text
<run-evidence>/phases/<phase-id>/result.json
```

Current-run result decisions are:

```text
EXECUTED
REUSED
REVALIDATED
```

A result records the phase and definition version, PASS/FAIL status, effective
input fingerprint, reusable evidence digest where applicable, decision reason,
source revision, start/end time, duration, and output identities.

`plan.json` contains active planning decisions plus explicit `SKIP` entries.
`evidence-manifest.json` contains the validated active required phase results.
The human-readable report renders both completion status and the phase plan.

When reusable evidence is consumed, source evidence and any required reusable
outputs are materialized into the current run. Deleting
`out/certification-cache` after a retained PASS therefore does not remove the
run-local provenance required by dependent certification.

## 10. Same-run resume

`--resume` means continuation of one retained run, not cross-run cache access.
Its run context must match:

```text
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

The four profile values prevent an evidence directory from being resumed under
a different proof scope.

A same-run phase marker is accepted only when the command signature matches and
the corresponding current-run PASS result still uses the current phase
definition version. Cross-run content-addressed reuse does not depend on these
markers.

## 11. Failure semantics

The implementation fails closed when:

- graph validation or dependency production fails;
- a required fingerprint or immutable tool/image identity cannot be established;
- a phase command fails;
- successful execution cannot produce valid current-run evidence;
- reusable PASS evidence cannot be published atomically;
- cached outputs no longer match their required immutable identity/location;
- registry revalidation fails and fresh publication also fails;
- complete image-lock construction is incomplete or invalid;
- a plan contains duplicate or unknown phases;
- an active required phase is missing, failed, or has an old definition version;
- final retained evidence cannot be written atomically.

A corrupt reusable object is a cache miss rather than a PASS. The requirement
executes normally; if that execution fails, the run fails.

## 12. Verification seams

`test-local-certification-incremental.sh` verifies graph policy, profile
selection, deterministic fingerprints, QuickFIX-only and shared-input
invalidation, trading-day independence, corrupt/old evidence rejection,
registry revalidation, image-lock construction, cold execution, warm reuse, and
required-phase completeness.

`test-local-certification-reuse-safety.sh` verifies producer failure propagation,
conditional-context failure behavior, image location plus immutable identity,
deployment-only build independence, run-local materialization, and retained-run
operation after the reusable cache is removed.

`test-local-certification-skip-semantics.sh` verifies that every full-profile
requirement omitted by explicit operator choices has exactly one SKIP plan
entry, representative reasons remain stable, Matching-only keeps its required
Matching image, SKIP creates no PASS result, and resume context includes the
proof-profile flags.

`test-local-production-like.sh` remains the broad orchestration contract. Local
Resource Lifecycle CI executes these focused contracts and the live kind
registry/resource lifecycle smoke test.

## 13. Acceptance mapping

| Issue #185 criterion | Repository verification |
| --- | --- |
| Cold run keeps existing proof | Same operator runner; FRESH runtime policy; broad contract and live kind smoke |
| Unchanged warm run can reuse valid work | Planner cold/warm contract |
| FRESH executes for each new run | PhaseGraph and Planner contracts |
| QuickFIX-only change has narrow invalidation | Per-service fingerprint contract |
| Shared-contract change invalidates affected images | Shared Spring-input contract |
| Trading-day/deployment-only change avoids unrelated builds | Fingerprint contracts |
| Missing registry digest is not stale reuse | Registry revalidation contract |
| Corrupt or wrong cache cannot false-PASS | EvidenceStore and definition-version contracts |
| Explicit skip differs from reuse | SKIP plan contract and PARTIAL report semantics |
| `--resume` remains same-run | Profile-bound run context and marker/result validation |
| Retained PASS survives cache deletion | Reuse-safety retained-evidence contract |
| Decisions and timing are explainable | `plan.json`, result JSON, report, and evidence manifest |
| Relevant lifecycle CI passes | Exact-final-head Local Resource Lifecycle CI |

## 14. Deferred work

The following remain out of scope:

- remote or distributed reusable cache;
- reuse of database, Kafka, Gateway, Matching ownership, or critical-consumer
  runtime state;
- environment-fault evidence reuse without a separately specified environment
  identity and revalidation rule;
- unbounded parallel image builds or Kubernetes mutations;
- a second certification pipeline.

Bounded parallel scheduling should be considered only if real cold/warm timing
shows that remaining independent phases dominate wall-clock time.

## 15. Completion gate

Issue #185 reaches the repository implementation gate when the final rewritten
branch head has:

1. architecture and implementation specifications aligned with code;
2. incremental, reuse-safety, skip-semantics, and broad contracts passing;
3. Local Resource Lifecycle CI passing, including the live kind smoke;
4. every additional workflow triggered by the final tree passing;
5. a diff review finding no duplicated cache policy, hidden second pipeline,
   reusable runtime-state phase, or shell failure path that can report success
   after a required operation fails;
6. milestone-oriented commit history following repository commit conventions.

Actual workstation cold/warm wall-clock measurements remain operational evidence;
the CI contracts do not fabricate those measurements.
