# Gateway admission completion specification

This specification defines the remaining repository-owned work for GitHub issue #135. The existing
QuickFIX Gateway operational code already owns admission state, readiness evaluation, operator
commands, audit records, and automatic safety actions. This change completes the missing trading-day
close coordination and certifies the existing behavior without creating another production module.

## Current implementation

The existing Gateway operations module is the application seam for trading admission. Its public
behavior is `status`, `open`, `pause-new-orders`, `interrupt-market`, and `close-day`, together with
normalized `TradingSystemObservation` reports. `TradingSystemStatusEvaluator` remains the single
place that turns Risk, Matching fleet, Kafka, and critical-consumer observations into open, pause,
or interrupt decisions.

The existing normalized observation interface is intentionally independent of Kubernetes and Kafka
client types. Infrastructure-specific collection is not part of this issue.

## Scope decision for issue #160

Issue #160 is not included in this implementation wave. Its live Kubernetes, Kafka, and service
observation adapters remain blocked by issues #154 through #159 and must be added later as adapters
to the existing `TradingSystemObservation` seam. Issue #160 must not duplicate readiness policy or
introduce another admission state machine.

This issue may add certification-side observation code where needed to prove #135 behavior, but
that code is test infrastructure and must not be presented as the production live-observation
implementation required by #160.

## Module and seam design

No new Gradle subproject or standalone coordination service is introduced.

The Gateway operations module remains the deep module that hides admission state transitions,
consecutive-ready checks, automatic safety actions, close retry behavior, and audit recording behind
its existing application interface.

Risk Admission already owns deterministic Open and Close Barrier construction and durable outbox
publication. The cross-service seam for final close therefore exposes only the operation the Gateway
needs: request closure of one trading session. The Gateway depends on a transport-neutral port;
production uses the existing Risk gRPC channel as its adapter. Risk maps that RPC to the existing
`TradingSessionBarrierService`. Tests use an in-process adapter or fake at the same seam.

The Gateway never constructs Matching commands and never writes Risk's outbox. Risk remains the
publisher of Close Barriers and Matching remains their consumer.

## Close workflow

Closing a trading day has two ordered responsibilities:

1. Stop new-order and cancellation admission by moving the Gateway to `CLOSED`.
2. Request Risk to durably persist the deterministic Close Barrier set for partitions 0 through 14.

The first responsibility is fail-closed and must not be rolled back if the second responsibility is
temporarily unavailable. The close request is idempotent: retrying after a timeout or process
restart may observe that some or all barrier rows already exist and must still be treated as the
same trading-session close operation.

Automatic session-end close and explicit `close-day` use the same close workflow. If the Risk call
fails after admission is closed, later monitor cycles retry the close request. A successful close
request means Risk accepted durable publication responsibility; it does not mean every downstream
consumer has already drained.

Deployment certification must separately prove that the accepted Close Barriers are published to
all 15 `matching.commands` partitions, applied by Matching, and followed by drained critical
consumer progress.

## Readiness and state behavior

The existing admission contract remains unchanged:

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

## Verification seams

Tests verify behavior through stable interfaces rather than implementation details:

- `GatewayOperationalController` for operator commands, automatic protection, close retry, and
  no-auto-reopen behavior;
- the Risk trading-session gRPC interface for close request validation and idempotent delegation;
- `TradingSessionBarrierService` for deterministic durable barrier insertion;
- FIX ingress through `GatewayAdmissionGate` for state-dependent new-order and cancellation
  admission; and
- the deployment certification interfaces for Kafka publication, Matching closure, and critical
  consumer drain.

Focused tests must cover successful close, retry after a temporary Risk failure, repeated close,
automatic session-end close, restart after session end, malformed session identity, and retained
admission behavior in every gate state.

## Deployment close certification

`scripts/end-to-end/critical-consumers/run-gateway-close-certification.sh` is a terminal capability
runner over the existing critical-consumer verification runtime. It does not provide production
observation adapters and does not duplicate Kubernetes, Kafka, or PostgreSQL access code. It reuses
the established observation collector, Gateway HTTP adapter, FIX submission client, Kafka position
collector, Matching committed-position collector, and critical-consumer progress collector.

The runner requires an already bootstrapped, lifecycle-labeled retained namespace with a clean
baseline. It performs the following observable sequence:

1. Collect three fresh normalized observations and explicitly open Gateway admission.
2. Submit one real FIX limit order and wait until Persistence reports the order as `RESTING`.
3. Snapshot all 15 `matching.commands` positions and invoke authenticated `close-day`.
4. Require every command partition to advance by exactly one record.
5. Require every Matching consumer to commit through its resulting command position.
6. Require all 15 Matching runtime reports to reach `CLOSED` with no pending input or publication.
7. Snapshot the resulting `matching.events` log end and require Persistence, Account, and QuickFIX
   consumer progress to catch up without quarantine.
8. Require the previously resting order to become `EXPIRED`.

This is intentionally the last capability executed against that retained trading session. A
successful close makes the Gateway and Matching session terminal for further admission work. The
certification does not require exactly-once network delivery to a disconnected FIX client; it proves
that the QuickFIX critical consumer durably consumes through the terminal event position.

The pure position-comparison rules used by the runner are fixture-tested by
`scripts/end-to-end/critical-consumers/tests/gateway-close-contract.sh` and are included in the local
resource lifecycle CI contract job.

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
