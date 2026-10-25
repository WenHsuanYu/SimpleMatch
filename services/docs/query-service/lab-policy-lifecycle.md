# Query Local Lab policy lifecycle

QS1-06 / [#193](https://github.com/WenHsuanYu/SimpleMatch/issues/193) verifies a controlled
policy replacement in a disposable, synthetic Local Lab. It uses the existing startup-only
Spring Environment policy, two Query replicas, Query-owned PostgreSQL projections and the
existing Kafka consumers. It does not add a runtime entitlement service or change trading
permissions. Production transport, revocation SLAs, business grant approval, OAuth, Gateway,
PKI and selective cache reintroduction retain their separate approval gates.

## Authorities and access during verification

The fixture provisioner creates credentials **before** either replica is deployed. Its
`application.properties` contains verifiers; `credentials.json` contains private client material.
All five Principals have the same immutable IDs throughout one run. Explicit next-generation
profiles replace every credential with zero overlap, first remove C's Y grant, then disable D.
They preserve the other grants and the operations-read permission. These are deliberate test
profiles, not a general account-grant approval interface or a production rotation requirement.

`policy-authority.py` owns a private, run-scoped **deployment approval record**, independent of
the application image. It pins namespace UID, image identity, policy version/content digest,
fixture identities and credential material. It does not become a second Spring configuration
source: each Java process still receives one complete validated policy at startup. Its owner
is the trusted synthetic Lab operator; protection against a malicious host/Kubernetes
administrator is outside this Lab's threat model.

The authority rejects older policy, same-version edits, incompatible image identity and
credential-content changes. A missing current record fails closed; another invocation cannot
initialize V1 in the existing authority directory. A surviving namespace or Query CRI task
also blocks a fresh run. Approval checks establish eligibility; the runner separately enforces
the activation state and verifies actual mounted policy bytes and HTTP policy-version headers.

All client credentials stay under owner-private `private/` directories, never in Git or public
reports. The verifier alone uses the new generation before Resume. Collector export is refused
until all eligible replicas are verified and Resume is recorded. Old read and operations
credentials must return 401 on every new replica; a failed verification never restores them.
This withholding is suitable only for the fully controlled synthetic clients in this Lab.

## State transitions and fencing

The retained timeline separates **revocation** from **service recovery**:

| Event | Required observation |
| --- | --- |
| Tsubmit | The next version/digest is approved in the private run record |
| Tblock | New connections, new requests on existing keep-alive connections and direct Pod paths reject work |
| Tdrained | Captured old containers/tasks/PIDs have stopped; no other Query task exists on any eligible worker |
| Trevoked | The old generation cannot generate another server-side response |
| Tresume | Every eligible new replica passes policy, grant, credential and catch-up verification; collectors may receive new credentials |

The runner stops the entire Query Deployment, so its Kafka consumers pause too. It uses
existing graceful shutdown, observes runtime container/PID identity rather than trusting
readiness or Pod deletion, and terminates an exact-owned old PID when necessary. The in-flight
scenario holds an Account table lock, verifies admitted JDBC work, and suspends one observed
old JVM to exercise forced drain. Direct access is tested independently of the Service.

The initial **20s drain**, **90s submit-to-stop verification** and **120s catch-up** limits are
Lab acceptance parameters. Exceeding one produces FAIL. A larger stop-verification window
cannot extend drain. The existing **5s read transaction/JDBC statement budget** is distinct
from total HTTP lifetime; the slow client intentionally waits longer. Already transmitted
bytes cannot be recalled. This proves bounded routine maintenance, not emergency immediate
cutoff, zero-downtime policy updates or a production revocation guarantee.

An intentionally unschedulable second V2 replica exercises partial activation. Collector
material remains withheld, old credentials remain rejected, and the authority records
`REVOKED_UNAVAILABLE`. Recovery retries the same approved V2; it cannot fall back to V1.
The runner then verifies C→Y 404, C→X 200 and B→Y 200 plus all five Principals' Account,
Market and Operations permissions on **each** serving replica. Disabling D is a separate
next-policy case, so grant revocation is not confused with principal disablement.

## Projection recovery and teardown

The test-only Java publisher sends acknowledged protobuf Account facts for X and Y through
the isolated Kafka, retaining event IDs, partition/offset, payload hashes and expected values.
It publishes another batch while Query is stopped. Restart uses existing inbox/checkpoint
semantics without rebuild, offset reset, projection deletion or changing Kafka source data.
The verifier captures all 15 Account source boundaries, compares durable checkpoints and exact
inbox receipts, then checks the values through HTTP. Empty partitions need no invented
checkpoint. This workload proves delivery/recovery for these captured Account inputs; it
does not certify every source event type or authoritative available trading capacity.

PostgreSQL and Kafka reuse the existing manifests with **run-owned emptyDir** storage in the
focused namespace. Their data survives Query-only replacement and is destroyed only with
the entire run. The runner uses the existing Query Flyway task before starting Query,
retains workload security settings and NetworkPolicies, and selects no unrelated service.
The canonical kind cluster is retained. Its kindnet does not prove NetworkPolicy enforcement.
The historical market artifact keeps its original 2026-08-11 date and checksum.

Teardown verifies namespace UID/ownership, actual Query/platform task exit and removal of
owned kubelet data directories. Only afterward does a second namespace use new identities,
new data and fresh V1. Retained private evidence is not a permanent cross-Lab identity registry.
On failure, preserve the FAIL report, stop exact-owned resources and keep reads unavailable;
do not repair the result by resetting offsets, restoring an old policy or deleting the cluster.

## Run and evidence

Read [deployment-test lessons](../../../docs/agents/deployment-test-lessons.md) first. Require
the healthy canonical three-worker Lab, the intended Docker context, disk/image-filesystem
headroom and no surviving Query security run. The wrapper selects only four declared phases
through the existing planner. Source and runtime execute fresh; image inventory/build may be
reused only through validated fingerprint/output identities. The test publisher is compiled
even when the boot image is reused. Actual platform-specific image execution is required on
every eligible worker before workload application.

Preflight renders the effective platform and Query configuration offline and checks required
ConfigMap/Secret references and keys, volume mounts and artifact `file:` URIs. All external
preflight commands have bounded deadlines. Require an existing **different** Query image as
the incompatible-rollback negative candidate (`--rejection-image`, default
`simplematch/query-service:local`). Preflight pins its immutable identity before any build or
namespace creation; the test never launches it. A missing candidate fails before deployment.
The focused image fingerprint includes both the normalization Dockerfile and its wrapper recipe.
Raw containerd image probes are owner-labelled and recorded before launch. Their exact task,
container and snapshot identities are cleaned and verified in `finally`, including ambiguous
launch/timeout before a namespace exists. Surviving probe identities block a subsequent run;
uncertain ownership is a failure, never a reason to delete an unverified resource.

```bash
GRADLE_USER_HOME="$HOME/.gradle" \
  bash scripts/run-query-security-lifecycle.sh \
  --context kind-simplematch-live --docker-context desktop-linux \
  --directory out/verification/query-security-lifecycle-NEW_RUN

python3 scripts/end-to-end/query-service/tests/security-fixtures-contract.py
python3 scripts/end-to-end/query-service/tests/policy-authority-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-deadline-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-cleanup-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-http-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-ownership-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-preflight-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-manifest-contract.py
python3 scripts/end-to-end/query-service/tests/lifecycle-probe-contract.py
bash scripts/end-to-end/query-service/tests/lifecycle-plan-contract.sh
```

The directory must be new. An interrupted run cannot be resumed by starting another V1.
Inspect retained ownership and actual processes before exact-owned cleanup; never force
namespace finalizers or prune the canonical cluster. CLI contract tests use synthetic
observation facts and establish only CLI behavior; they are not runtime attestations.

The evidence directory records planner decisions/fingerprints, source HEAD/worktree, fixture
class identity, runtime image identities/execution probes, HTTP matrices, Kafka receipts and
catch-up, state-transition proofs and teardown. The private authority binds every proof to
its run, namespace UID, image, policy and activation/verification IDs; it accepts only ordered
observations from the trusted Lab adapter. It is not cryptographic remote attestation.
Inspect `runtime/verdict.json` and all required phase results rather than one successful probe.

| #193 requirement | Enforcement / evidence |
| --- | --- |
| A3; policy anti-rollback | Authority CLI rejection tests, same-version/lost-record/image rejection report |
| INV-06/07; Block and drain | Real keep-alive/direct HTTP, CRI task and PID observations, ordered T events |
| Credential lifecycle | Controlled provisioner tests, zero-overlap generation replacement, collector withholding and all-replica 401 |
| INV-01/02/03; grant isolation | Five-principal HTTP matrix on every replica; separate disabled-D policy |
| Failed activation | Actual unschedulable replica, `REVOKED_UNAVAILABLE`, same-V2 recovery |
| INV-08; durable recovery | Paused-consumer publication, captured Kafka boundaries, exact inbox/hash and HTTP results |
| Run separation | Namespace/task/data teardown and disjoint identities in a new V1 namespace |
| Evidence / reuse | Four-phase planner, image execution, original FAIL reports and final owned-resource inventory |

QS1-07 / #194 remains responsible for integrating the ordinary certification collectors and
the complete Phase 1 coverage/evidence review. This focused lifecycle test does not replace it.
