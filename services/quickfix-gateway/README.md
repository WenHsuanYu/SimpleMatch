# quickfix-gateway

Java + Spring Boot + QuickFix/J based FIX 4.4 gateway.

This service is the target external FIX entrypoint in the current SimpleMatch architecture. It sits
in front of the event-driven trading pipeline, while `matching-engine` remains the native C++
matching core.

The cross-service admission, recovery, identity, and error-message rules are defined by
[Consistency, Recovery, Identity, and Error Boundaries](../docs/platform/consistency-recovery-identity-and-errors.md).
This README describes the Gateway-specific implementation of that policy.

## Role In The System

`quickfix-gateway` is responsible for:

- accepting FIX 4.4 sessions through QuickFix/J;
- validating and normalizing inbound FIX order flow;
- validating canonical UUID `Account(1)` before durable order admission;
- appending valid commands to the local command WAL before Risk submission;
- durably recording recovery state in a sidecar journal;
- mapping durable WAL commands directly to typed Risk v2 new/cancel commands;
- synchronously submitting those commands to the production Risk v2 admission service;
- reconciling indeterminate Risk outcomes against Risk's durable admission journal;
- returning terminal FIX acceptance or rejection only when the authoritative Risk outcome is known;
- keeping unknown new-order outcomes non-terminal rather than fabricating a business rejection; and
- consuming `matching.executions` and mapping them back to outbound FIX responses.

The former `orders.commands` compatibility publisher is retired. The Gateway has no runtime switch
that can re-enable that Kafka publication path.

The runtime QuickFIX session config defaults to `config/quickfix/acceptor.cfg`, which uses
`../../config/quickfix/fix-spec/FIX44.xml` as the shared FIX dictionary.

Session-aware deployment baseline:

- Stateful deployments should use stable owner ids that match pod identity, such as
  `quickfix-gateway-0` and `quickfix-gateway-1`.
- Client session routing should target the owner service name, for example
  `quickfix-gateway-owner-0:5001`.
- Startup recovery completes before the QuickFIX acceptor starts, and readiness becomes healthy only
  after recovery succeeds.
- The default QuickFIX acceptor configuration uses continuity defaults
  (`ResetOnLogon/Logout/Disconnect=N`) for same-owner restart semantics.
- Standby promotion, fencing, and route transfer remain follow-up work documented in
  `docs/quickfix-gateway-session-scale-plan.md`.

## Durable Admission And Recovery

For a valid inbound command, the write-before-submit ordering is:

```text
1. force command WAL
2. force recovery sidecar UNKNOWN
3. map the WAL record to the typed Risk v2 command
4. start the Risk v2 RPC
```

The command WAL records what normalized command was received. The sidecar records what the Gateway
knows about Risk ownership or outcome. Sidecar states are `UNKNOWN`, `PENDING`, `ACCEPTED`, and
`REJECTED`.

The WAL is a Gateway-owned persistence model, not a serialized Risk protobuf. Its current local
`schemaVersion = v1` identifies the stable flat WAL JSON format; it does **not** mean that the
Gateway submits a v1 Risk contract. Live submission and restart resubmission both use the same
`RiskCommandMapper` and `RiskCommandSubmitter` to produce typed v2 `NewOrderCommand` or
`CancelOrderCommand` messages directly from the durable WAL record.

Startup recovery is state-aware rather than a blind WAL replay:

- a WAL record with no sidecar state means the process stopped before Risk submission started;
  startup writes `UNKNOWN` and performs the first submission;
- `UNKNOWN` is reconciled with `GetAdmissionOutcome(command_id)` before any retry decision;
- `PENDING` proves Risk durably owns the command and is never retry permission;
- `ACCEPTED` and `REJECTED` are terminal and are not resubmitted;
- authoritative `NOT_FOUND` permits resubmission only after local `UNKNOWN`, reusing the original
  `command_id`; and
- local `PENDING` combined with authoritative `NOT_FOUND` is an ownership contradiction and prevents
  successful startup.

`simplematch.quickfix-gateway.replay-enabled` controls this startup recovery phase and defaults to
`true`.

## FIX Outcome Behavior

The Gateway distinguishes authoritative business outcomes from transport uncertainty.

- Risk `ACCEPTED`: the Gateway can emit the normal accepted/PendingNew FIX response and register the
  accepted order context.
- Risk `REJECTED`: the Gateway can emit the terminal business rejection with a stable client-facing
  reason.
- Transport timeout, connection loss, exhausted retry, or breaker-open conditions: the Gateway uses
  internal `UNKNOWN`; these conditions do not prove Risk rejected the command.

For a new order whose Risk outcome is unknown, the current FIX response remains non-terminal
`ExecutionReport(PendingNew)` with:

```text
SYSTEM_ERROR: order outcome is pending confirmation; no client action is required
```

For a cancel whose outcome is unknown, the Gateway does not fabricate an `OrderCancelReject`.
Background reconciliation does not currently guarantee a later FIX follow-up message after an
unknown outcome becomes terminal.

Operator logs retain the detailed transport or dependency reason needed for diagnosis; FIX client
text intentionally does not expose internal RPC, service, breaker, retry, database, or stack-trace
details.

## Identity

The external FIX/WAL order id remains deterministic `OrderID = O-<ClOrdID>`.

At the Risk v2 boundary, `RiskOrderIdentityDeriver` derives a separate opaque internal order UUID
from FIX session identity, the deployment-owned trading day, and the original client `ClOrdID`.
That configured day must identify the same session as the approved Market Reference artifact; it is
not inferred from the wall clock or WAL timestamp. New and cancel commands for the same FIX order
on the same trading day therefore map to the same internal Risk order identity without changing the
FIX-facing `OrderID` contract.

Canonical account identity is owned by Account Service. FIX `Account(1)` carries the canonical UUID;
the Gateway validates and preserves it. The Gateway does not derive an account UUID from a human-
readable alias such as `ACC-1`.

## Kafka Boundary

QuickFIX Gateway no longer publishes accepted FIX commands to `orders.commands`. The authoritative
asynchronous order path begins after Risk durable admission:

```text
Gateway WAL
  -> RiskCommandMapper
  -> v2 NewOrderCommand / CancelOrderCommand
  -> Risk v2 admission journal
  -> Risk terminal journal + transactional outbox
  -> CDC / Kafka orders.validated
  -> matching-engine
```

The production Gateway-to-Risk admission path no longer constructs the v1 `OrderCommand` message.
A shared v1 adapter may remain elsewhere as an explicit compatibility utility, but it is not part of
QuickFIX live submission, WAL recovery, or Kafka routing.

## Implemented Behavior

The Java gateway includes:

- QuickFix/J acceptor lifecycle wiring and session logging;
- capability configuration through Spring Config Data;
- `NewOrderSingle (35=D)` and cancel request ingestion;
- gateway-local validation before WAL append;
- command WAL plus append-only recovery sidecar;
- direct WAL-to-v2 Risk command mapping;
- v2 Risk submission with bounded retry and breaker protection;
- Risk admission reconciliation during startup recovery;
- canonical Account UUID validation before durable admission;
- deterministic FIX `OrderID = O-<ClOrdID>` plus a separate internal Risk order UUID;
- `matching.executions` consumption and outbound FIX mapping; and
- `/healthz`, `/healthz/liveness`, `/readyz`, and `/metrics` management endpoints.

Two intentional Java-specific differences from the historical C++ gateway baseline are documented
and tested:

- WAL is stored as one-line JSON records rather than the older plain-text format.
- Baseline `PendingNew` uses `ExecID = E-<recordId>` so outbound acknowledgements stay traceable to
  persisted WAL state.

## Configuration

Configuration is resolved only through Spring Config Data. Use canonical Spring properties or
relaxed environment names such as `SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID`; JSON config discovery and
legacy `SIMPLEMATCH_FIX_*` aliases are not supported.

Important Spring properties:

- `simplematch.quickfix-gateway.acceptor-enabled`: start or skip the QuickFix/J acceptor;
- `simplematch.quickfix-gateway.data-plane-enabled`: enable or skip `matching.executions` consume
  wiring;
- `simplematch.quickfix-gateway.replay-enabled`: run state-aware WAL recovery before readiness;
  default is on;
- `simplematch.quickfix-gateway.owner-id`: stable logical gateway owner identity; the default Kafka
  consumer group for `matching.executions` follows this owner id;
- `simplematch.quickfix-gateway.ingress.trading-day`: required deployment-owned trading day used by
  new orders, cancels, replay, and accepted-order session identity; production-like Kubernetes
  wiring reads it from the immutable `matching-session-config` shared with Risk and Matching;
- `simplematch.grpc.targets.risk-service`: gRPC target used for Risk submission and reconciliation;
  and
- `simplematch.quickfix-gateway.risk-client.*`: deadline, bounded retry, and breaker settings for
  Risk calls.

There is no `compatibility-publish-enabled` setting. Supplying a legacy value cannot restore the
retired publisher because production wiring no longer creates a Kafka command publisher.

Default paths from `QuickFixGatewayFileProperties` include:

- QuickFIX config: `config/quickfix/acceptor.cfg`;
- WAL path: `data/quickfix/wal/inbound.wal`; and
- the recovery sidecar is maintained beside the configured WAL by Gateway recovery wiring.

## Run

Start the service normally:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun
```

Start it without opening the FIX acceptor socket:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false'
```

Start it with the acceptor and matching-execution consume path disabled for a dry run:

```bash
SIMPLEMATCH_QUICKFIX_GATEWAY_INGRESS_TRADING_DAY=YYYY-MM-DD \
  ./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false --simplematch.quickfix-gateway.data-plane-enabled=false'
```

Disabling `replay-enabled` disables startup WAL recovery and should not be treated as a normal
production recovery mode.

## Verification

Run the full module test suite:

```bash
./gradlew :services:quickfix-gateway:test
```

Run the dedicated QuickFIX/J certification-style simulator evidence:

```bash
./gradlew :services:quickfix-gateway:certificationTest
```

The verification suite covers the normal FIX path together with durable WAL ordering, direct v2
command mapping, state-aware recovery, identity validation, and the separation between authoritative
Risk outcomes and transport uncertainty. Current CI evidence belongs in execution/certification
material rather than this README.

## Scope Boundary

This README describes the current Java target gateway in this repository.

- It does not claim venue-specific external certification.
- It does not claim a post-reconciliation FIX follow-up protocol that is not implemented.
- It does not expose `orders.commands` as a supported publication or recovery path.
- It is the target runtime architecture for the FIX boundary in this repo.
