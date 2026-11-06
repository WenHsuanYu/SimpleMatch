# Query-service deployed verification

The current S5 R18/R19/R21 deployment owner is
[`RestingBuyKubernetesTest`](../../tools/kubernetes-tests/src/test/java/com/simplematch/tools/kubernetes/RestingBuyKubernetesTest.java).
The selected Gradle entry builds source-bound images, creates a disposable Kubernetes namespace,
runs the deployed checks and verifies cleanup. It uses the same run for the FIX business chain,
Query read/replay, Redis outage and Query policy replacement.

```bash
./gradlew verifyKubernetes -PkubernetesScenario=resting-buy --no-daemon
```

Use the [deployment-test lessons](../agents/deployment-test-lessons.md) before a live run.
The command needs the canonical kind runtime and its deployment prerequisites.
The run must use fresh source and image receipts. Inspect its `summary.json`, diagnostics,
scenario observations and cleanup result. A focused `verifyKubernetes` PASS cannot replace
the final `verifyAll` acceptance required by Issue #202 S5.

## Current deployed assertions

- **R18 public business reads:** A real FIX BUY/SELL cross produces a filled Order and nonempty
  Executions in Query's PostgreSQL projection. Authorized HTTP reads verify Order parentage,
  Account grants, Market Reference and source freshness. The same business data remains available
  during the run-owned Redis outage.
- **R18 active isolation:** The verifier reduces Query to zero Pods, fences original container
  and PID identities, then submits a public FIX IOC. Risk, Matching, Account, Persistence and
  FIX outcomes must complete while Query is unavailable.
- **R18 deterministic replay:** The verifier captures earliest and end offsets for all
  15 partitions of `matching.events` and `account.lifecycle`. An independent operator
  credential resets only Query projections. Both Query consumer groups must reach the captured
  end boundaries. Authenticated business data, including nonempty Executions, must match the
  baseline. The verifier disables the operator adapter and restores two Query replicas.
- **R19 policy replacement:** The selected owner performs V1, V2 and V3 policy activation with
  two new Query Pods per generation. It verifies their image and controller identity, credential
  invalidation, changed Account grants, and disabled Principals. The complete five-Principal
  route matrix checks 140 HTTP cells per generation.
- **R19 transport and catch-up:** The owner establishes usable keep-alive and direct Pod
  connections before a stop. It verifies blocked transport and a 20-second process fence.
  During each stopped window, the owner publishes acknowledged synthetic Account events.
  The new generation must recover exact Query inbox rows, event hashes, checkpoints and
  authenticated Account views within the bounded catch-up window.
- **R21 serving:** Both Query Pods must preserve Ready and liveness behavior, image identity
  and authenticated operations Freshness while Redis is unavailable.

The Query service's HTTP/JDBC suites remain the component owners for authentication,
authorized parent lookups, minimal envelopes, status codes and local PostgreSQL behavior.
The Query fixture provisioner and `certification_security.py` remain inputs to the
independent production-like certification and credential validation workflows.

## Retired standalone operators and explicit coverage loss

Issue #202 S5 retired `scripts/end-to-end/query-service/run-certification.sh` and its
dedicated Shell helpers and contracts after a selected source-bound Jupiter Live PASS.
It also retired the focused `scripts/run-query-security-lifecycle.sh` Python Local Lab,
its transport harness and isolated lifecycle contracts. These entries no longer provide
current operator commands. The selected Gradle owner covers the required business,
policy-generation, transport-block and durable catch-up behaviors.

The old Query Shell operator also sampled a quiescent zero-Pod window repeatedly.
It recorded every Matching Pod, PVC/PV placement, consumer offset and critical workload
identity during that window. The new selected test checks real trading progress during the
Query outage and the run-owned health/cleanup invariants. It does not reproduce that
quiescent multi-sample report. The old Python Lab also ran a second fresh namespace with
disjoint synthetic identities after first-run teardown. The selected Jupiter run owns
one fresh namespace per execution. It does not publish the old Lab's second-namespace
verdict schema or reproduce its independent image-import probe reports.
The retired scripts' exact CLI, JSON evidence formats and source-layout contracts are
intentional verification losses under the 2026-11-02 S5 requirement disposition.

## Selected Live evidence and limits

The source-bound selected run at commit `cdab22d2` used run
`dde70b10-bcd0-4aef-bea3-b951339b68a2`.
Its retained evidence directory is
`out/verification/issue202-risk-cas-fixed-20261106-r1/`.
It reports `assertions=PASS`, `diagnostics=PASS`, `cleanup=PASS`.
The V1/V2/V3 matrix records 420 observed HTTP cells. The run also records the
real FIX cross, Query replay, blocked transport and stopped-window Account catch-up.

The stopped-window Kafka publications are synthetic test inputs.
They do not establish an Account Authority outbox publication path.
The Account money oracle compares one durable outbox protobuf event with its Query projection.
It does not establish correctness of the aggregate Account limit ledger.
This selected Live PASS does not certify a later source revision or complete S5/P6 acceptance.
