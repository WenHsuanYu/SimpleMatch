# Gateway admission completion specification

This specification defines the remaining repository-owned work for GitHub issue #135. The existing
QuickFIX Gateway operational code owns admission state, readiness evaluation, operator commands,
audit records, and automatic safety actions. This change completes trading-day close coordination
and certifies the behavior without creating another production service or readiness implementation.

## Current implementation

The Gateway operations module is the application seam for trading admission. Its public behavior is
`status`, `open`, `pause-new-orders`, `interrupt-market`, and `close-day`, together with normalized
`TradingSystemObservation` reports. `TradingSystemStatusEvaluator` remains the single place that
turns Risk, Matching fleet, Kafka, and critical-consumer observations into open, pause, or interrupt
decisions.

The normalized observation interface is intentionally independent of Kubernetes and Kafka client
types. Infrastructure-specific production collection remains outside this issue.

## Scope decision for issue #160

Issue #160 is not included in this implementation wave. Its live Kubernetes, Kafka, and service
observation adapters remain blocked by issues #154 through #159 and must later adapt deployed facts
to the existing `TradingSystemObservation` seam. Issue #160 must not duplicate readiness policy or
introduce another admission state machine.

Issue #135 may use certification-side collectors to prove deployed behavior. Those collectors are
test infrastructure and are not the production live-observation implementation required by #160.

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
- recovery never opens automatically.
- status older than five seconds requires a new-order pause.
- an oldest pending critical event warns at 30 seconds and requires a pause at 120 seconds.
- identity, schema, artifact, algorithm, image, topology, quarantine, or deterministic payload
  conflicts require interruption according to the existing evaluator.
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

The completion evidence does not claim that #160 live observation adapters are implemented, nor does
it claim external production promotion.
