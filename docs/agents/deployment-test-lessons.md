# Deployment Test Lessons

This is the living checklist for Compose, Docker, kind, and Kubernetes deployment tests. It records
repeatable mistakes and their prevention checks so a later run does not rediscover them.

## Run preflight

Before applying deployment resources or injecting a deployment fault:

1. Read the lessons below and run every prevention check that applies to the selected profile.
2. Confirm the Docker daemon, canonical kind cluster, Kubernetes context, namespace, and expected
   worker topology before selecting any Pod, Node, container, image, PVC, or topic.
3. Resolve runtime identities from the deployed artifact and API objects. Do not substitute a unit
   test fixture, expected ordinal, or remembered symbol for an observed assignment.
4. Confirm that the run has an isolated namespace and clean, run-owned evidence directory. Reuse
   prior phase evidence only when the runner's resume contract verifies the same source, cluster,
   trading day, and namespace.
5. Inventory Docker resources and generated caches before and after the run. Cleanup must preserve
   active resources and remove only exact run-owned disposable resources.

If a preflight check fails, stop before fault injection and record the result as an environment or
precondition failure. Do not make the test green by changing Kafka offsets, deleting authoritative
data, weakening fail-closed behavior, or guessing a replacement target.

## Recurring lessons

| ID | Symptom | Root cause | Prevention check | Safe fix | Last verified |
| --- | --- | --- | --- | --- | --- |
| DT-001 | Matching reports `kUnknownInstrument` during deployed E2E | A unit-test instrument such as `XTAI/2330` was sent to a partition that did not own it | Read the approved artifact's `routingPolicy.assignments` and select an instrument whose `partitionId` equals the target partition; assert the same value in the helper | Fix the E2E target selection. Preserve the Matching core's rejection and fail-closed behavior | 2026-08-15 |
| DT-002 | Kubernetes commands fail, or the API refuses connections | Docker is stopped, the kind cluster is absent, or the active context is not canonical | Run `docker info`, verify `kind-simplematch-live`, verify the current context, and verify one control plane plus three labelled workers before deployment work | Restore the daemon or select the verified canonical context; do not recreate or delete resources during a failed preflight | 2026-08-15 |
| DT-003 | A failed E2E contaminates later replay or makes a corrected test fail immediately | The first run wrote invalid records to the authoritative Matching topic and the consumer reused its stored offset | Before rerun, check the topic/run isolation and consumer boundary; never reuse a poisoned run as if it were clean | Create a fresh run-owned E2E environment or use the runner's verified resume path; do not edit offsets manually | 2026-08-15 |
| DT-004 | Matching fleet evidence is inconsistent with the intended topology | A StatefulSet was temporarily scaled, a debug Pod remained, or a PVC/PV was selected by name alone | Require 15/15 Ready Pods, unique Pod indexes, exact Pod UID to Node to PVC to PV mapping, and no unowned debug workload before E2E | Restore the run-owned StatefulSet and remove only the exact run-owned debug resource, then recapture the baseline | 2026-08-15 |
| DT-005 | Native configure or image build is killed by the host | Parallel compilation exceeds the local memory budget | Check available Docker/host memory and use the documented bounded parallelism for the selected preset before starting the build | Lower build parallelism or adjust the local resource budget; do not misclassify exit 137 as a source failure | 2026-08-15 |
| DT-006 | Cleanup removes useful state or leaves disk usage unexplained | A blanket prune was used without checking references and generated caches | Inventory container references, image IDs, volume ownership, builder cache, and generated build directories before cleanup | Remove exact stopped/run-owned resources and stale duplicate tags only when unreferenced; verify remaining inventory and reclaimed space | 2026-08-15 |
| DT-007 | Deployed Matching exits with SIGSEGV during OpenSSL cleanup | The binary used static vcpkg OpenSSL while system libcurl brought a separate dynamic OpenSSL dependency into the same process | For the Matching image, verify the curl dependency is resolved through the same vcpkg dependency graph and inspect `ldd /app/simplematch-matching` before deployment | Build curl through vcpkg, use config-mode CURL discovery, and remove the system libcurl development/runtime packages from the Matching image | 2026-08-15 |
| DT-008 | Process-crash E2E reports no restart after an apparently successful `kubectl exec kill` | The restricted Matching container did not expose a reliable in-container signal path for terminating PID 1; the fault command returned success without an observed container restart | Resolve the exact Pod UID, Node, and running container ID from Kubernetes and the matching kind worker before injecting the fault; require restart-count evidence afterward | Use the worker's CRI client to stop only that exact container with zero graceful-stop timeout, then verify the same Pod UID returns Ready and replays | 2026-08-15 |
| DT-009 | A run-owned Kubernetes helper cannot write its report under `/tmp` | The helper used a read-only root filesystem but the test Pod did not mount a writable scratch volume | For every run-owned helper, check its declared writable report directory before copying or executing the test binary | Mount a run-owned `emptyDir` at `/tmp`; keep the application workloads read-only and remove the exact helper after the run | 2026-08-15 |
| DT-010 | PostgreSQL and dependent workloads enter CrashLoopBackOff after Docker/kind recovery | The Docker Desktop data disk was full, so the kind workers could not write local-path PVC or runtime files; local-path PVC requested sizes are logical reservations, not preallocated per-volume capacity | Before deployment or fault injection, verify usable space on each kind worker's `/var` filesystem and retain a documented safety margin; also compare the aggregate local-path PVC request envelope with the Docker disk-image capacity | Expand the Docker disk image or remove only inventoried stale Docker resources, then wait for the full baseline to recover before resuming tests | 2026-08-15 |
| DT-011 | A deployed E2E report appears to prove recovery but cannot identify whether events belong to this run | The helper measured batch elapsed time and accepted any consumed event; the collector did not require full-fleet or Node/PVC/PV continuity evidence | Decode each Matching event, correlate it to a submitted `source_command_id`, record the per-event measurement definition, and reject metrics without all 15 runtime snapshots and the selected target's same-Node/PVC/PV identity | Strengthen the helper and collector before rerunning only the E2E phase; do not reinterpret an older report as proof of the new evidence contract | 2026-08-15 |
| DT-012 | A newly created kind control plane never becomes healthy and kubelet reports `overlay ... invalid argument` while creating Pod sandboxes | Docker's relocated data root was on an NTFS filesystem; images remained readable, but nested containerd overlay mounts used by kind could not be created reliably | Before creating kind, verify `docker info` storage root and `findmnt -T <DockerRootDir>`; require a Linux filesystem such as ext4 for the Docker data root and verify a disposable nested container before deployment | Move Docker data to a Docker Desktop-supported Linux VM disk or Linux filesystem, restart the daemon, delete only the failed canonical cluster, and rerun the repository cluster preflight | 2026-08-15 |

## How to add a lesson

Add a row only when the failure is repeatable or materially risky. Each row must state:

- the observable symptom;
- the concrete root cause, separated from unrelated defects;
- a preflight check that can catch it before deployment or fault injection;
- a safe fix that preserves authoritative data and fail-closed behavior;
- the date and a redacted evidence source.

Do not put credentials, bearer tokens, Secret values, raw FIX payloads, complete account payloads, or
unredacted environment variables in this file or its evidence links. Review this table when changing
deployment scripts, certification profiles, image/build workflows, or cleanup behavior, and remove
or revise entries when the prevention check no longer matches the implementation.

## Disk hygiene boundary

Disk cleanup is an inventory operation, not an availability shortcut:

- Preserve running containers, the canonical kind cluster, images loaded into it, active Compose
  volumes, and caches required by a running build or test.
- Compare image IDs/content, not tags alone. If duplicate tags point to one image and no active
  resource references the older tag, retain the newest tag and remove the stale alias.
- Treat generated `out/`, `build/`, `vcpkg_installed/`, Gradle, and Docker builder cache as
  disposable only after confirming the current or next requested workflow does not rely on them.
- After cleanup, record what was removed, what was retained, and the measured space change.
