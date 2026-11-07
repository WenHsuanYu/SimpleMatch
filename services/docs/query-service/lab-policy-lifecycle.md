# Query policy replacement and historical Local Lab

**Current owner:** [`KubernetesQueryPolicyReplacement`](../../../tools/kubernetes-tests/src/test/java/com/simplematch/tools/kubernetes/KubernetesQueryPolicyReplacement.java)
in the selected [`RestingBuyKubernetesTest`](../../../tools/kubernetes-tests/src/test/java/com/simplematch/tools/kubernetes/RestingBuyKubernetesTest.java).
Issue #202 S5 retired the independent Python Local Lab and
`scripts/run-query-security-lifecycle.sh` after source-bound selected Live acceptance.
Their former commands, CLI tests and report formats are historical.

The current deployment command is:

```bash
./gradlew verifyKubernetes -PkubernetesScenario=resting-buy --no-daemon
```

Read [Query deployed verification](../../../docs/verification/query-service-certification.md)
for the current R18/R19/R21 assertions, execution scope and evidence limits.

## Current R19 behavior

The selected test uses the same owned Kubernetes namespace as the public FIX and
Query replay scenarios. Its fixture defines two distinct Accounts and five Principals.
Policy versions V1, V2 and V3 replace credentials without overlap. V2 revokes an
Account grant while retaining a positive read through an independent permitted Principal.
V3 disables a Principal. The test checks the complete authorization matrix against
each new Query Pod, including denied cross-Account access, old credential rejection,
Market Reference and operations-read restrictions.

Before each replacement, the test records two old Query Pod and container identities.
It proves real HTTP/1.1 keep-alive and direct Pod connectivity before the stop.
It then reduces the Deployment to zero Pods. It checks blocked transport and a
20-second fence for old containerd tasks and Linux PID identities.
Each new generation must have two Ready replicas with the expected policy and image.
All replicas must complete the observed authorization matrix.

While Query is stopped, the selected test publishes new synthetic Account lifecycle
protobuf events to the real Kafka broker. It records acknowledgments, partitions,
offsets, event IDs and SHA-256 digests. The stopped consumer checkpoints must remain
unchanged. After restart, Query must recover matching durable inbox rows, source
checkpoints and Account projections within the bounded catch-up interval.
The events are test fixtures published directly to Kafka. They are not evidence
that the Account Authority outbox published those events.

Issue #202 retired the independent Python fixture provisioner and policy validator.
The selected Jupiter run owns private Query credentials, policy snapshots, and
run-owned FIX material. Historical Lab bundles are not inputs to current acceptance.

## Historical accepted Lab evidence

The original #193 Local Lab completed a four-phase run in October 2026.
Its retained `runtime-r13` report is under
`out/verification/issue-193-lifecycle/runtime-r13` at source
`f0cbe6270a1d66453fdcf42f05f849a488a1f83c`.
That original source established two policy-generation replacements,
the five-Principal matrix, direct/keep-alive blocking, bounded old-process
drain and exact Account Kafka recovery. Its second namespace used
new Principals, new Accounts and fresh projection data.
Those historical results do not certify the current source.

The newer selected Jupiter Live run used commit `cdab22d2` and run
`dde70b10-bcd0-4aef-bea3-b951339b68a2`.
Evidence under `out/verification/issue202-risk-cas-fixed-20261106-r1/`
records V1/V2/V3 matrices with 420 verified HTTP cells and two
stopped-window Kafka catch-up checks. The result reports assertions,
diagnostics and cleanup PASS. The original Lab's independent second
namespace, per-worker image-import probes and old report/phase schema
were not copied to Jupiter. A separate second-namespace failure matrix
is no longer a current S5 core acceptance requirement.

The focused PASS cannot become an aggregate S5/P6 PASS.
Production external transport, real Account Authority publication,
operational revocation SLAs and customer credential governance remain
outside the scope of both Local Lab and selected Jupiter evidence.
