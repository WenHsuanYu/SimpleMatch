# Deployment Test Lessons

This is the runtime-scoped preflight and prevention catalog for Compose, Docker, kind, and
Kubernetes tests. It retains only platform/environment risks and distributed-system transients
with lasting, evidence-backed prevention value. Track individual failures and deterministic
product, verifier, fixture, or workflow defects in their owning Issue or run evidence.

## Run preflight

Before applying deployment resources or injecting a deployment fault, check only the selected
runtime and operation. These are entry checks, not a second certification suite:

1. **Docker-only:** Confirm that the selected Docker daemon and context are usable.
2. **Compose:** Confirm the Docker prerequisites and identify the exact Compose project and
   configuration being applied. Do not check kind or Kubernetes.
3. **kind:** Confirm the intended cluster and Kubernetes context. Check Docker availability
   when creating the cluster, loading images, or managing its containers. Check node topology
   only when the selected scenario requires it.
4. **Kubernetes:** Confirm API access, context, and namespace for the selected target. Do not
   require a local Docker daemon or inspect unrelated nodes.
5. **Changes, cleanup, or fault injection:** Identify the exact live target and its ownership
   before acting. Resolve Pod, Node, container, image, PVC, or topic identity only when that
   resource is part of the operation. Do not use guessed ordinals or fixture identities.
6. **Full certification:** Follow the selected certification runbook for source/image
   identity, required topology, isolated namespace, new run-owned evidence, and cleanup.
   Reuse a runtime only for a documented focused diagnostic with current ownership and input
   checks. A focused or offline result cannot establish a complete deployed PASS.

Read the retained lessons below. Run a prevention check only when its failure mode applies
to the selected runtime and scenario. Trading-day, delivery-manifest, barrier, control-plane
stability, and worker image-execution checks belong to scenarios that depend on them, not
to every Docker or Compose preflight. For helper images required on kind workers, verify
actual execution on each eligible worker, not only image metadata. When creating kind on
Docker Desktop, share only `/tmp/simplematch-kubernetes-coordination` with the VM and
verify its private permissions and ownership. This step does not apply to Native Docker.

The selected Kubernetes Jupiter run has no capacity-based preflight gates. Do not restore
removed memory, CPU, Docker storage, or worker image-filesystem gates. Its required source,
image, topology, ownership, and runtime failure checks remain in force.

If a required preflight fails, stop before the affected operation and record a precondition
failure. Do not alter Kafka offsets or authoritative data, guess replacement targets, weaken
assertions, or extend timeouts to conceal a defect. Inventory resources before scoped cleanup;
remove only exact run-owned disposable resources and verify what remains.

## Retained environment and distributed-system lessons

These entries apply only when the selected operation matches their scope. They are not a list of
mandatory checks for every deployment. Individual failures, failed attempts, test fixes, and
acceptance results belong in their owning Issue or run evidence, not in this living catalog.

### DT-025 — Kafka peer DNS after broker runtime recreation

- **Scope:** KRaft broker/runtime recreation or topic provisioning after node disruption.
- **Signal:** A broker remains in `CrashLoopBackOff` with peer-name `UnknownHostException`;
  the provisioning Job times out even after Kubernetes DNS records recover.
- **Cause:** The affected JVM retained a negative peer-DNS result after runtime recreation.
- **Check:** Confirm broker-side resolution of all `kafka-{0,1,2}.kafka-headless` names and
  continuous readiness of all three brokers before dependent provisioning.
- **Safe response:** Once DNS is healthy, restart only the affected broker Pod and preserve its
  PVC. If scoped recovery fails, preserve diagnostics and propose a cluster rebuild, but do not
  execute it without explicit workstation-owner authorization. Never change Kafka data or
  offsets to force a pass.
- **Evidence:** 2026-08-31, redacted
  `out/certification/fresh-20260828-0827/kafka-failure/one-broker-loss/matching.commands.topic.txt`.

### DT-028 / DT-038 — Cold worker image preparation

- **Scope:** First use of a pinned Debezium or Matching image on a cold kind worker.
- **Signal:** `kafka-connect` rollout or `matching-fixture-publisher` readiness exceeds its
  bounded wait; the same image later reaches Ready or Completed.
- **Cause:** A cold pull and unpack exceeded the original readiness window. This alone does
  not establish corrupt image content.
- **Check:** For the affected workload, verify the exact digest and whether the target worker
  already has a usable image. Distinguish pull/unpack latency from application startup failure.
- **Safe response:** Allow the exact pinned image to finish pulling or pre-pull only that
  verified identity, then rerun with new source-aligned evidence. Keep the configured timeout
  and the original failure record. Do not alter Kafka data, offsets, or image identity.
- **Evidence:** DT-028, 2026-08-31,
  `out/certification/issue-137-query-20260831-desktop-r1/logs/kubernetes-risk-outbox-connector.log`;
  DT-038, 2026-09-16,
  `out/certification/issues-158-159-20260916-r1/logs/kubernetes-open-barriers.log`.

### DT-045 — Native Docker BuildKit DNS isolation

- **Scope:** This Native Docker workstation, when BuildKit fetches Gradle Wrapper inputs.
- **Signal:** `Dockerfile.flyway-runner` cannot resolve `services.gradle.org`, although the
  host and an ordinary Docker container can resolve it.
- **Cause:** The default BuildKit RUN network fails DNS resolution in this environment.
  The upstream resolver cause is not established.
- **Check:** Compare DNS lookup in a disposable default-network BuildKit build,
  `docker build --network=host`, and an ordinary container. Do not touch canonical resources.
- **Safe response:** Only when this failure is reproduced, use
  `-PkubernetesBuildNetwork=host` for a fresh local `verifyAll` run. Leave hosted CI defaults
  unchanged. Do not disable Gradle Wrapper integrity or change global Docker DNS settings.
- **Evidence:** 2026-11-11, two failed full-local builds and isolated default-network RED /
  host-network GREEN checks in `out/ci-investigation/s6-buildkit-dns-20261111/`.

### DT-046 — Stale kind network identity after Docker node IP changes

- **Scope:** Canonical kind nodes with changed Docker-side IPs after runtime or network changes.
- **Signal:** Nodes report Ready but Kafka quorum or CoreDNS peer resolution fails.
- **Cause:** Observed kindnet/kube-proxy host-network Pod IPs and CNI IPAM subnet values
  disagreed with current Node IPs and PodCIDRs. The long-term fix is not established.
- **Check:** Compare each Node's `spec.podCIDR` and `InternalIP` with the exact node's
  `/etc/cni/net.d/10-kindnet.conflist`, host-network Pod IP/host IP, and `ip -4 route`.
  Confirm cross-node PodCIDR routes and CoreDNS reachability. Node Ready alone is insufficient.
- **Safe response:** Preserve cluster, registry, data, and diagnostics. Do not patch CNI files,
  routes, or restart shared components without a scoped plan and authorization. If approved,
  back up exact configurations, repair only proven mismatches, and verify persistence across
  reconciliation. Do not claim the repair is durable without a fresh source-aligned run.
- **Evidence:** 2026-11-11, runs
  `b34e0031-bb75-4291-b1f9-09ba52c797cf` and
  `5562aa7f-2297-45ee-9909-8581ea9164d9` (FAIL/FAIL/PASS/PASS);
  `ebdce8af-f407-4ae6-9b0b-2fd28784e569` reached Kafka 3/3 and Matching 15/15
  before a later R19 failure.

## Recording and retirement policy

A lesson is an actionable prevention rule, not a chronological incident log. Retain an entry
only when all of these conditions hold:

1. It describes a platform/environment failure or a distributed-system transient that can
   still recur after deterministic product, verifier, fixture, or workflow fixes.
2. It identifies the specific environment or operation that activates the check. It must
   not impose a full-environment preflight on an unrelated task.
3. It provides an observable symptom, an evidence-supported cause (or explicitly states
   uncertainty), a focused check, and a safe response that preserves authoritative data.
4. It gives a date and a traceable, redacted evidence source. A date alone is not sufficient.
5. The check is not already fully enforced by a current owner such as a lifecycle manager,
   automated test, or certification runbook.

Keep the original DT ID for a retained entry. Use the same six fields: Scope, Signal, Cause,
Check, Safe response, and Evidence. Do not renumber entries when one is removed. Record every
new deployment failure in its owning Issue or run evidence regardless of whether it qualifies
for this catalog. When a fix makes an entry obsolete, retire it here; the original incident
remains in Git history and its owning evidence. Do not preserve historical-only rows or
tombstones in this file.

Do not put credentials, bearer tokens, Secret values, raw FIX payloads, complete account
payloads, or unredacted environment variables here or in linked evidence. Review retention
when deployment scripts, certification profiles, image workflows, or cleanup rules change.
For image, cache, and disk cleanup, follow the
[local registry and resource lifecycle](../operations/local-registry-resource-lifecycle.md)
and the ownership rules in [AGENTS.md](../../AGENTS.md); do not duplicate them here.
