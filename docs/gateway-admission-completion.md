# Gateway admission completion specification

This specification records Gateway completion for #135 and its live-observation follow-up #160.
Existing QuickFIX Gateway operational code owns admission state, readiness evaluation, operator commands,
audit records, and automatic safety actions. This change completes trading-day close coordination
and certifies the behavior without creating another production service or readiness implementation.

## Current implementation

The Gateway operations module is the application seam for trading admission. Its public behavior is
`status`, `open`, `pause-new-orders`, `interrupt-market`, and `close-day`, together with normalized
`TradingSystemObservation` reports. `TradingSystemStatusEvaluator` remains the single place that
turns Risk, Matching fleet, Kafka, and critical-consumer observations into open, pause, or interrupt
decisions.

The normalized observation interface is intentionally independent of Kubernetes and Kafka client
types. Issue #160 supplies production HTTP and Kafka adapters behind that interface without moving
transport types into the admission domain.

## Live observation implementation for issue #160

The Gateway now collects production observations through the existing
`TradingSystemObservation` seam. Risk exposes its startup-verified daily identity, every Matching
owner atomically publishes a runtime document, Kafka Admin supplies required topology, log ends, and
consumer-group commits, and each critical consumer exposes process-local pending ages plus durable
quarantine state. Kafka group commits, not the process-local offset cache, are authoritative after a
consumer restart. The Gateway normalizes those transport-specific facts and leaves all readiness
policy in `TradingSystemStatusEvaluator`; there is still only one admission state machine.

The Matching status sidecar serves only an `emptyDir` observation volume mounted read-only, not the
PVC that contains baseline metadata. The 15 Matching requests and the remote critical-consumer
requests run concurrently. A single request is bounded to one second and the complete observation
is bounded to three seconds, which must remain shorter than the five-second freshness threshold.
Collection and stale monitoring use separate scheduler threads. Missing, malformed, incomplete, or
timed-out input therefore cannot refresh readiness: the last complete observation expires and the
existing automatic safety action pauses new orders.

Trading identity comparison belongs to Risk and Matching: each Matching owner must report its
actual session, artifact, schema, algorithm, and image identity for comparison with Risk's verified
daily identity. Kafka and critical-consumer observations have no trading identity field and require
no independent full trading identity attestation. Adapters must not copy Risk identity into those
observations. Kafka supplies availability, topology, and progress; consumers supply progress,
freshness, pending ages, and quarantine state. Existing event validation and conflict handling remain
required. Consumer-detected processing conflicts surface through durable quarantine; the Kafka
Admin API alone cannot prove that no event ID/payload conflict has ever occurred.
The source-aligned local PRE_OPEN-to-explicit-open smoke below satisfies the deployed admission
criterion. Remote delivery status is tracked in #160; external production certification and the
business command in #162 are separate work.

Matching command progress comes from Kafka's acknowledged offsets for
`matching-partition-consumer-0` through `matching-partition-consumer-14`. Native
`next_commit_offset` is only a pending commit candidate: it legitimately becomes `null` after
acknowledgement and must never stand in for the domain's `committedOffset`. All fifteen group reads
start before the adapter waits for results. Missing committed progress for a non-empty command log
fails closed; neither a pending candidate nor the log end is substituted for missing evidence.

The local Kubernetes overlay uses a 23:59 lab session close time so a fresh admission smoke can run
after the real-market 13:30 cutoff. Automatic close, the independent stale monitor, and all freshness
limits remain enabled; other overlays keep the normal session policy. This local window does not
certify the production close schedule or authorize automatic opening.

The older certification-side collector remains test infrastructure. It is not called by the
production adapter and cannot publish on behalf of a running Gateway.

### Live-enabled startup and ownership observations

The live status HTTP client owns its JSON-tree parser independently of Spring MVC's JSON binding.
Its composition therefore does not require a framework-provided Jackson 2 `ObjectMapper` bean.
`QuickFixGatewayLiveObservationApplicationTest` starts the live-enabled application composition
with only polling and delivery-plane ports isolated; it does not substitute the document client or
the deadline-bound collector.

Matching's process `runtime_state` is not an admission permission. A previously confirmed owner
can remain process-ready during the existing five-second lease-renewal uncertainty grace period,
while its current `ownership_permitted` fact is false. The encoder preserves false ownership and
recovery facts rather than throwing on that combination. The Gateway still rejects unsafe
admission through its existing evaluator, and native self-fencing deadlines remain unchanged.

The first 2026-10-05 deployed bootstrap exposed both the missing parser dependency and the encoder's
incorrect READY invariant. Its failed report and previous Gateway/Matching startup logs remain in
`out/certification/issue-160-smoke-bootstrap-20261005-r1/`; its disposable namespace and owned PVCs
were removed. Those failed observations remain regression evidence, not a successful deployed open.

The next source-aligned bootstrap (`d55809b`) completed all 46 selected phase results, including all
six first-attempt migrations, workloads, CDC delivery, and the Matching fleet. Its report remains
`PARTIAL` because Compose was explicitly skipped. The subsequent actual collector smoke exposed the
commit-candidate/committed-position mismatch and remained `PRE_OPEN` without sending an open command.
Its evidence is retained under `out/certification/issue-160-admission-smoke-20261005-r2/`: the original
Pod template was restored with a clean canonical diff, the owned operator Secret was deleted, and
the retained evidence contains no token. A fresh source-aligned path is required after this adapter
fix; the failed smoke cannot be relabeled as a successful deployed open.

### Deployed admission smoke

On 2026-10-06 Asia/Taipei, source `6b624259aeca2d0917b86ca46f6e70c78fca0d03` completed a
fresh disposable-namespace bootstrap and actual production-collector smoke. Bootstrap evidence is
retained in `out/certification/issue-160-smoke-bootstrap-20261005-r3/`; all 46 selected phase results
are `PASS`. Its aggregate report is `PARTIAL` because Compose was explicitly skipped, not a
`full-local` certification. All six migrations succeeded on their first observed Pod with exit 0
and no restart. All fifteen Matching Pods and Gateway were Ready with zero restarts before smoke;
the pinned helper and Matching images also executed on every eligible worker.

`out/certification/issue-160-admission-smoke-20261005-r3/report.json` records the separate admission
smoke as passing. Unauthenticated status returned 401. Authenticated status remained `PRE_OPEN`
through three healthy samples; status polling did not create observations or open trading. The
production collector supplied the existing qualifying observations. One explicit authenticated
`open` returned `accepted: true`, `gateState: OPEN`, `reason: OPENED`, and `OPEN_ELIGIBLE` with no
unsafe reasons. Eight further healthy OPEN samples spanned seven seconds, exceeding the unchanged
five-second expiry, and the database retained an `ACCEPTED / OPEN` audit for the operator command.
No synthetic observation was posted and no monitor or automatic-close setting was disabled.

The temporary change enabled HTTP and referenced a run-owned operator Secret only. Cleanup restored
the original Pod template with an empty canonical diff, removed the Secret and private token/header
files, and found no token value in retained bootstrap/smoke evidence. This verifies the tested
local admission path, not production HA, current-market calendar/data accuracy, or an end-to-end
business order. The input was the existing approved 2026-08-27 FINAL artifact, not a fabricated
current-day artifact. Failed earlier runs remain retained separately. Documentation-only evidence
and runbook updates do not change the tested executable behavior; the deployed source revision
above remains explicit.

The later stale-recovery qualification fix is verified separately through the public controller,
not by relabeling this deployed smoke. It re-evaluates previous evidence before replacing it with a
new report, so an expired healthy streak cannot count toward reopening. Controller regressions
cover three new healthy reports after a monitor-induced pause, the same reporting gap before the
monitor runs, read-only status polls, no implicit reopening, and the unchanged exact five-second
boundary. This recovery fix has local test evidence; it was not deployed in the smoke recorded
above.

### Observation contract baselines

`scripts/end-to-end/critical-consumers/tests/system-observation-contract.sh` runs independent named
cases in subshells, so each case owns its temporary files and transport stubs. Run the script without
arguments for the complete suite, or pass case names such as `json_encoding consumer_progress` for a
focused check. Static input fixtures and expected outputs live under
`scripts/end-to-end/critical-consumers/tests/baselines/system-observation/`; they are reviewed files,
not expectations generated from the implementation under test.

The certification-side JSON encoder takes one normalized-source file and writes one observation.
The encoding case compares its complete output with `gateway-observation.json`, including independently
supplied Risk and Matching identities and the absence of Kafka/consumer identity fields. Progress and
diagnostic cases likewise compare generated outputs with their baselines. Before `diff -u`, `jq -S`
sorts object keys only: array order, values, missing fields, and extra fields still matter. Dynamic
capture times are checked through named ordering predicates rather than exact wall-clock timestamps;
the raw timing document and ordering log are also retained for inspection.

`collector_success_path` fixes the clock and replaces only external capture/offset-lookup seams. It
runs the real source validation, 15-owner assembly, consumer normalization, and encoder, then compares
both normalized sources and the final observation with complete baselines. This covers wiring that a
direct encoder test alone cannot prove.

Each invocation prints a review directory under `out/contracts/system-observation.*`, containing
the canonical expected and actual JSON plus a diff for each comparison. A mismatch prints the diff
and exits unsuccessfully. A changed contract requires deliberate review of both the implementation
and its baseline; the script never updates baselines automatically. These local reports prove the
test-infrastructure contract, not live collection by the deployed Gateway.

## Module and seam design

No new Gradle subproject or standalone coordination service is introduced.

The Gateway operations module remains the deep module that hides admission state transitions,
consecutive-ready checks, automatic safety actions, close retry behavior, and audit recording behind
its existing application interface. A package-private `TradingSessionCloseCoordinator` owns only the
process-local close-request lifecycle: pinning the first usable trading-session identity, applying a
bounded retry schedule, and stopping retries after either acceptance or a permanent failure.

Risk Admission owns deterministic Open and Close Barrier construction and durable outbox
publication. The cross-service seam exposes only the operation the Gateway needs: request closure of
one trading session. Production uses the existing Risk gRPC channel through
`TradingSessionClosePort`; Risk maps that RPC to the existing `TradingSessionBarrierService`.

The Gateway never constructs Matching commands and never writes Risk's outbox. Risk remains the
publisher of Close Barriers and Matching remains their consumer.

## Close workflow

Closing a trading day has two ordered responsibilities:

1. Stop new-order and cancellation admission by moving the Gateway to `CLOSED`.
2. Request Risk to durably persist the deterministic Close Barrier set for partitions 0 through 14.

The first responsibility is fail-closed and is never rolled back if the second responsibility is
temporarily unavailable. The first usable trading-session identity is pinned for the process-local
close request so a later observation cannot redirect a retry to another session.

The Risk close request is idempotent. The Gateway gRPC adapter classifies only statuses that are safe
to retry, such as `UNAVAILABLE` and `DEADLINE_EXCEEDED`, as temporary. Risk maps only explicit
persistence or transaction availability failures to `UNAVAILABLE`; data-integrity and unexpected
server failures remain `INTERNAL`, while an invalid trading-session request remains
`INVALID_ARGUMENT`. Retry attempts are bounded and spaced by the close coordinator. Permanent
failures stop automatic retry while Gateway admission remains `CLOSED`.

Automatic session-end close and explicit `close-day` use the same close workflow. The scheduled
`monitor()` path owns pending close retries. Read-only `status()` does not perform network calls or
advance the retry state.

A successful Risk close request means Risk accepted durable publication responsibility; it does not
mean every downstream consumer has already drained. Deployment certification proves downstream
publication and drain separately.

## Readiness and state behavior

The admission contract remains:

- `PRE_OPEN` rejects new orders and cancellations.
- `OPEN` accepts new orders and cancellations.
- `NEW_ORDERS_PAUSED` rejects new orders while retaining cancellation admission.
- `MARKET_INTERRUPTED` rejects both new orders and cancellations.
- `CLOSED` rejects both and cannot reopen in the current Gateway process.
- `open` requires three consecutive fresh `OPEN_ELIGIBLE` observations and an explicit operator
  command.
- previous qualification is discarded when the preceding observation has become unsafe or expired,
  including a reporting gap with no monitor cycle; status queries do not create qualifying checks.
- recovery never opens automatically.
- status older than five seconds requires a new-order pause.
- an oldest pending critical event warns at 30 seconds and requires a pause at 120 seconds.
- Risk/Matching identity, schema, artifact, algorithm, or image disagreement requires interruption.
  Kafka topology, critical-consumer quarantine, and reported deterministic payload conflicts also
  require interruption according to the existing evaluator.
- zero market activity is valid when committed and end offsets agree and no pending-event age
  exists.

A Gateway process that starts after the configured session close time remains fail-closed: the next
fresh observation or monitor cycle closes admission and requests the same Risk-owned idempotent close
workflow. It does not require a prior `OPEN` transition.

## Verification seams

Tests verify behavior through stable interfaces rather than implementation details:

- `GatewayOperationalController` for operator commands, automatic protection, query behavior,
  close scheduling, and no-auto-reopen behavior;
- `TradingSessionCloseCoordinator` for identity pinning and bounded retry;
- the Risk gRPC adapter for temporary versus permanent transport failure classification;
- `TradingSessionBarrierService` for deterministic durable barrier insertion;
- FIX ingress through `GatewayAdmissionGate` for state-dependent new-order and cancellation
  admission; and
- the deployment certification interfaces for Kafka publication, Matching closure, and
  critical-consumer drain.

Focused tests cover successful close, retry after a temporary Risk failure, bounded retry,
permanent-failure termination, repeated close, automatic session-end close, restart after session
end, close ordering, and side-effect-free status queries.

## Retained-run provenance

Gateway close certification is a continuation of one completed production-like run, not an operation
that may attach to any disposable namespace. The dependent runner therefore requires the retained
production-like evidence directory as an explicit input.

Before any Gateway, FIX, or Kafka helper state is changed, the runner verifies:

- the retained `run-context` names the requested namespace;
- the retained `source-revision` equals the current repository `HEAD`;
- the current repository has no tracked, staged, or untracked non-ignored changes under the
  certification runtime source paths; an uncommitted documentation-only change outside those paths
  does not change this run's runtime source identity and does not block provenance; and
- the retained verifier image reference and immutable image identity are present and well formed.

Registry transport retains the digest-qualified verifier reference. The `kind-load` compatibility
path separately records the verifier OCI image identity because its local tag is mutable. After a
kind-loaded helper Pod becomes Ready, certification resolves the node that runs the Pod and compares
the retained image identity with the CRI image identity on that node. This one-time check closes
mutable-tag ambiguity without treating the Pod `imageID` as an equivalent digest or adding a
recurring observation loop.

The runtime source scope is deliberately broad: it includes all non-documentation, non-generated
repository inputs that can affect images, manifests, configuration, or certification harnesses,
including Compose, FIX dictionaries, and dependent Query/critical-consumer runners. The shared
provenance helper excludes Markdown/documentation and `graphify-out/`, so an uncommitted editorial
change does not block the current runtime certification. A documentation-only commit still changes
Git `HEAD`, so a retained run must be recreated after that commit before dependent certification;
the boundary concerns runtime semantics and source identity, not byte-for-byte image layers, because
Docker build contexts may still contain documentation files.

The close runner initializes its own empty evidence directory before retained-run validation. A
provenance or namespace preflight failure therefore still produces the same machine-readable
`verdict.json` shape as later certification failures, while no application or helper state has yet
been mutated.

This prevents a custom production-like evidence path from being confused with the default evidence
directory, and prevents an uncommitted or untracked runtime harness change from being presented as
evidence for the recorded revision without coupling runtime certification to unrelated documentation
edits.

## Deployment close certification

`scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh` is a terminal capability
runner over the existing critical-consumer verification runtime. It reuses the normalized
observation collector, Gateway HTTP adapter, prepared FIX client, warm Kafka observation adapter,
Matching runtime evidence, PostgreSQL consumer-progress evidence, and the established exact-event
inbox check.

The runner is organized as explicit phases for retained-run preflight, baseline validation, client
preparation, Gateway opening, order submission, session close, Matching proof, terminal-event proof,
and verdict publication. The phases share the existing verification interfaces instead of creating a
second cluster-access or readiness framework.

The runner requires an already bootstrapped, lifecycle-labeled retained namespace with a clean
baseline and performs this observable sequence:

1. Validate retained source, namespace, verifier-image reference and identity, and clean source
   state.
2. Collect three fresh normalized observations and explicitly open Gateway admission.
3. Submit one real FIX limit order and wait until Persistence reports the order as `RESTING`.
4. Snapshot all 15 `matching.commands` log ends through the warm Kafka observer and invoke
   authenticated `close-day`.
5. Require every command partition to advance by exactly one record.
6. Require every Matching consumer to commit through its resulting command position.
7. Sample all 15 Matching runtimes in parallel and require `CLOSED` with no pending input or
   publication.
8. Require the previously resting order to become `EXPIRED` and retain its terminal Matching Event
   identity.
9. Capture one post-close `matching.events` log-end snapshot and require Persistence, Account, and
   QuickFIX progress to catch up with no current or historical quarantine.
10. Require the selected order's terminal Matching Event to appear exactly once in each critical
    consumer inbox.

The runner intentionally does not maintain a second pre-close/post-close event-movement probe.
Expiration of the selected order identifies the terminal event causally, the post-close log end
establishes the durable drain boundary, and the exact inbox check proves that all three critical
consumers processed that same terminal event.

The warm Kafka observer remains alive for the readiness and terminal close phases, avoiding repeated
Kafka CLI/JVM startup. Matching runtime samples are parallelized rather than issuing 15 serial
`kubectl exec` calls per poll. Helper cleanup is ownership-aware and bounded: the runner deletes the
Kafka observer only if that run created it and waits for the fixed-name Pod to disappear. It also
waits for the temporary Gateway operations overrides to roll back before a PASS verdict can be
published.

This capability runs last against the retained trading session. A successful close makes Gateway and
Matching admission terminal for that process/session. Exactly-once network delivery to a
disconnected FIX client remains out of scope; durable QuickFIX consumer progress is the required
boundary.

A typical invocation is:

```bash
scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh \
  --namespace "$SIMPLEMATCH_CERTIFICATION_NAMESPACE" \
  --retained-evidence-dir "$SIMPLEMATCH_CERTIFICATION_EVIDENCE_DIR" \
  --evidence-dir "$GATEWAY_CLOSE_EVIDENCE_DIR" \
  --timeout-seconds 300
```

## Completion gate

Issue #135 can close when all of the following are true:

- the close workflow is wired through the owned Risk transport without duplicating barrier logic;
- focused QuickFIX Gateway and Risk tests pass;
- the existing state-machine, stale-status, lag, mismatch, zero-activity, audit, and ingress tests
  remain green;
- deployment certification proves the accepted close workflow reaches all 15 Matching partitions
  and drains the required critical consumers;
- repository static analysis, Flyway checks, documentation checks, and `git diff --check` pass; and
- GitHub Actions for the final pull-request head pass.

The #135 evidence remains scoped to close coordination. Issue #160 separately owns production live
observation and its deployed PRE_OPEN-to-explicit-open smoke; neither claim implies external
production promotion.
