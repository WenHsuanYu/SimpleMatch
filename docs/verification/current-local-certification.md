# Current Development Verification and Throughput

**Current baseline, 2026-10-20.** The complete local development aggregate, all six dependent
business scenarios, restoration and evidence audits passed. The existing native and deployed
Matching throughput gates also passed and their measurements are recorded below. This record
replaces r13/r14 as the current documentation baseline; their original reports and verdicts remain
historical evidence in the [evidence audit](local-certification-evidence-audit.md).

The accepted scope is development-environment correctness, cross-service integration and the
repository's existing throughput measurements. Read the [execution runbook](local-production-like-certification.md)
and [Phase DAG contract](local-certification-phase-dag.md) for current commands and execution rules.
The runtime-tested revision remains authoritative after a later documentation-only commit.
Evidence is retained in this workspace's ignored `out/`; another checkout needs the evidence bundle.

The certification tooling changed on 2026-10-28.
The dated results below retain their original source, schema, cache decisions, and outcome.
They do not establish a new PASS for the simplified runner.
Current diagnostics require schema version 2 results from a fresh run.

## Identity and complete aggregate

| Field | Recorded value |
| --- | --- |
| Runtime-tested source | `239e7b9b592e66669acd0f5332fa5cf4bdbc2fdc` |
| Aggregate run ID | `20261020-033756-224519` |
| Aggregate evidence | `out/certification/current-runtime-20261020-r6/` |
| Execution, snapshots, audits and scenarios | `out/verification/current-runtime-20261020/r6/` |
| Command | `bash scripts/run-local-production-like-certification.sh --keep-resources` |
| Start / finish, Asia/Taipei | 2026-10-20 11:37:56 / 2026-10-20 11:56:22 (UTC+08:00) |
| Start / finish, UTC | 2026-10-20T03:37:56.458005+00:00 / 2026-10-20T03:56:22.274613+00:00 |
| Kubernetes context | `kind-simplematch-live`; one control plane and three workers |
| Disposable namespace / UID | `simplematch-local-cert-20261020-033756-224519` / `a4bd165a-9175-4356-8eab-a8172026675b` |
| Approved artifact day | `2026-08-27`; the historical artifact retains its original day |
| Delivery manifest | `tools/market-reference-builder/data/2026-08-27/delivery/manifest.yaml` |
| Delivery-file SHA-256 | `3356157968e680790adc604b093e1dab9bcbd4e5cc98f708c6e14af6362a2d13` |
| Artifact-content SHA-256 | `f14ea4d735ac962abf9295b2cced30337b89114fef1edd9202aa88915e2094e4` |
| Aggregate evidence-manifest SHA-256 | `38f601f9fb79796323375eeb4634dfb1eedc0e0489b57dd86087b1914c0bcd3d` |
| Local image-lock SHA-256 | `5335fa5f2eaaa8a07c89052728c2bd7878fb47d27469e7c0be96e4629b51ac95` |
| Immutable verifier reference | `localhost:5001/simplematch/risk-matching-e2e-verifier@sha256:640a6446eb2569655a52cad4a2b7be2e89a560eb75ddf8b42ad35f1291e76b35` |

The actual runner exited **0**, with **PASSED** and **62/62
required phases**. The graph, plan, result set and manifest agree. The official plan records
**37 EXECUTE, 17 REUSE, 8 REVALIDATE, zero SKIP**.
Reusable evidence passed declared input/dependency/output/identity contracts; revalidation also
performed the current external check. Every FRESH phase executed in this run.

Coverage includes Compose dependency/CDC/failure paths, image preparation and publication,
fresh Kubernetes inputs/platform/workloads, CDC delivery, Open Barriers and all 15 Matching
owners. All **six Flyway Jobs** succeeded; first-attempt success is
`true` in `migration-job-review.json`.
Kafka topic bootstrap is a separate Job. The corrected Flyway image packages its dependency
closure for offline migration from fresh writable state.

`audit/evidence-audit-final.json` independently validates required phases, definitions, policy,
dependency order, fingerprints, EvidenceStore objects, materialized outputs and provenance with
no errors. It is a retained-file audit. Live before/after namespace, workload and Pod snapshots
bind each scenario to the same run, namespace UID and immutable image references. Metadata
binding does not replace executable-image proof; the separate current helper probes execute the
pinned images on all eligible workers.

## Business and restoration checks

All scenario processes exited zero. Their public pure verifiers, restoration and before/after
runtime binding passed. Times below use Asia/Taipei; paths are relative to `r6/gates/`.

| Scenario | Verdict / actual process | Restoration | Evidence / completion |
| --- | --- | --- | --- |
| Query reads, Redis outage/rebuild and active processing | PASS; exit 0 | Verified | `query/`; 2026-10-20 12:03:01 |
| Market-data replay and Redis repair | PASS; exit 0 | Verified | `market-data/`; 2026-10-20 12:04:50 |
| Public stream disconnect, worker replacement and resubscription | PASSED; exit 0 | Verified | `streamer-recovery/`; 2026-10-20 12:06:56 |
| Matching recovery, new cancel and controlled redelivery | PASS; exit 0 | Verified | `matching-recovery/`; 2026-10-20 12:09:24 |
| Public FIX resting BUY and durable business effects | PASS; exit 0 | Verified | `resting-buy/`; 2026-10-20 12:10:57 |
| Same-owner Gateway protocol and business recovery | PASS; exit 0 | Verified | `gateway-recovery/`; 2026-10-20 12:13:04 |

Query passed all nine named checks, including a public FIX BUY/SELL cross with durable FILLED
views and a new IOC event processed while Query had zero Pods. Market Data passed all seven
checks, including deterministic complete-snapshot replay and Redis repair. Streamer checks also
inspect actual client snapshots, six signals, group assignments and replacement timing.

The normal BUY intentionally remains resting with its durable reservation and zero fills.
Matching recovery proves one new public cancel and controlled byte-identical redelivery without
duplicate business effects; `restartCausedRedeliveryProven: false` retains the exact evidence
boundary. Gateway recovery proves the same logical owner/PVC and protocol/business continuity.
Both recovery records retain `postRestorationOpenProven: false`: restored configuration and Ready
state are distinct from an OPEN trading gate. Cleanup discards only the disposable test runtime.

## Development throughput measurements

These measurements follow existing repository gates. There is **no fixed minimum TPS criterion**
in those gates. Their acceptance requires loss/duplicate/determinism integrity and the existing
local recovery bounds. A measured rate is a calibration for the stated workload and environment.

Native measurements use the existing **full-native-dev Debug build**, sanitizers OFF,
CPU `0` only, on **AMD Ryzen 7 5800X 8-Core Processor**, with GCC 13.3.0.
The measurements ran at source `61d79e1d49a166b059212beeb87a7e29b9322de9`;
`source-intervals.json` proves the selected benchmark and build inputs are identical at the
runtime-tested revision. The binary SHA-256 is `6900f873bd83e677c12dc5b81f661e9bc804dbd1c429dcb9b48cef802094d627`;
the build command found its native dependencies current. The benchmark fixes 150 books and
a 256 resting-order bound per book,
with a rest/cancel mix. Its process-call percentiles cover `core.process`; commands/second uses
the measured loop, including event encoding and integrity bookkeeping. Peak RSS includes the
benchmark fixtures, encoded evidence and replay allocations, rather than only Matching's core.

| Native profile | Measured commands | Commands/second | Core p50 | Core p99 | Core p99.9 | Core max | Peak process RSS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 100 warmup / 1,000 measured iterations | 300,000 | 53,902.835 | 4.488 µs | 11.863 µs | 14.728 µs | 54.763 µs | 421.95 MiB |

The default local-depth profile passed **10 cycles / 102,000 measured commands and events**:
rate min/median/max **41,825.639 / 60,425.763 / 61,727.134
commands/second**. Every cycle and the longer sample recorded zero loss, duplicates and
rejections, with matching replayed state, event fields and serialized bytes. Evidence:
`out/verification/current-runtime-20261020/throughput/`, including each cycle's JSON and the longer report.

Kafka measurements use the same tested source in an isolated Matching component environment,
namespace `simplematch-local-cert-20261020-031602-178244`, UID `703929c7-a6b0-49fb-b040-91891d2d057e`. Its explicit
`--matching-fleet-only --skip-compose` deployment remains **PARTIAL** because it deliberately
omits unrelated full-service gates; its deployment status is never upgraded to full-local PASS.
The independent component E2E process and read-only deployed collector both passed, each
with actual exit 0 recorded in `throughput-fleet-r2/measurement-controller.json`. Their reports
bind the 15-owner fleet, 5/5/5 worker placement, CPU allowances and runtime image evidence.

| Kafka measurement | Commands / expected events | Commands/second | p50 | p99 | p99.9 | Max |
| --- | --- | --- | --- | --- | --- | --- |
| Kafka → Matching → Kafka, before replacement | 8 | 15.333 | 521.572 ms | 521.628 ms | 521.628 ms | 521.628 ms |
| Kafka → Matching → Kafka, after replacement | 8 | 15.511 | 515.607 ms | 515.641 ms | 515.641 ms | 515.641 ms |

The existing helper uses **eight commands per batch in one partition**, before and after the
selected same-owner Pod replacement. Latency spans client publish submission to observation of
the correlated Matching event, including producer delivery/flush and consumer polling. With eight
samples, high percentiles are close to the maximum; they do not establish stable tail latency or
saturation throughput. This is the Kafka/Matching component path, not full FIX→Risk→Account TPS.
Both batches recorded zero losses and duplicates. Replay catch-up was
**2.993 seconds** (limit 60); replacement was
**34.842 seconds** (limit 120). Before/after ring occupancy and all
percentiles remain in `out/verification/current-runtime-20261020/throughput-fleet-r2/`.

## Repository checks

The Query change passed **7/7 owning shell contract suites** at `61d79e1`; its reviewed
input hashes still match the final source. The final phase-graph change passed **12/12 owning
certification-tool contracts** at the final committed source. ShellCheck, Markdown links,
diff checks and independent Standards/Spec review passed with zero unresolved findings. Query tests first
reproduced missing terminal FIX fields, absent-field restoration failure and a nonzero observer
whose stdout looked valid; each passed after the fix. Unrelated/missing terminal reports and
unsafe real consumer state still fail closed. The unchanged nine-check verdict was not weakened.
The FIX client retains its caller-owned initiator sequence state and waits for actual correlated
delivery. Query evidence binds the received completion to the original receipt and durable SENT intent.

Java/static-analysis evidence at source `88c22fafaf521b60d5ad6de6e5e83d79601f8645` retains
**907 tests, zero failures/errors/skips**, including 192 ordinary QuickFIX tests and five
certification cases. The final Java entrypoint reused valid unchanged Gradle outputs
(108 tasks: one executed, 107 up-to-date). This is not a forced rerun of all tests. The final source delta contains Query shell/contract files, the deployment lesson and the
Matching-only phase dependency regression. Java, Gradle and native source are unchanged.
`source-intervals.json` records exact equal Git objects and the native measurement interval. Retained native tests are **80/80 PASS** at
`99c57ad56747504e277f97dab95dff58d1acee86`, with the exact selected source comparison recorded
separately. The new source also has the actual runtime and throughput evidence above.

## Resource cleanup and historical integrity

The selected local full profile declares approximately **41.74 GiB** of steady memory requests
and **47.87 GiB including all Jobs**, against **38.26 GiB** of Desktop memory; its existing budget
warning remains visible. These are declared requests, not measured process RSS. Four kind nodes
share one host. Host memory, swap, VM image filesystem and control-plane stability were checked;
idle-memory recovery preserved container/volume/image identities and was followed by fresh probes.

After recording results, cleanup removed only the component namespace and the final full-run
namespace `simplematch-local-cert-20261020-033756-224519`, with their disposable PVC/PVs. UID/run ownership was
revalidated and no corresponding claim PV remains. Canonical cluster, registry, unrelated
resources, images and evidence were preserved. Before/after inventories and resource reports are
in `throughput-fleet-r2/cleanup/` and `r6/final-cleanup/`. Logical deletion does not imply an equal
reduction in the host's sparse Docker disk size.

All **988 original r13/r14 file hashes** matched the pre-work snapshot: 957 r13 and 31 r14.
The historical audit remains an as-of-2026-10-18 assessment: r13 has complete historical 61-phase
evidence; r14 has nine traceable phases and unconfirmed aggregate completion. This new baseline
supersedes their current-document role without reconstructing or changing those old records.
