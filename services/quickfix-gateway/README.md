# quickfix-gateway

Java + Spring Boot + QuickFix/J based FIX 4.4 gateway.

This service is the target external FIX entrypoint in the current SimpleMatch architecture. It sits in front of the
event-driven trading pipeline, while `matching-engine` remains the native C++ matching core.

## Role In The System

`quickfix-gateway` is responsible for:

- accepting FIX 4.4 sessions through QuickFix/J
- converting inbound FIX order flow into domain commands
- appending inbound traffic to the local WAL before attempting the first business-level FIX acknowledgement
- synchronously submitting new and cancel commands to `risk-service` over gRPC
- returning `ExecutionReport (PendingNew)` only after `risk-service` accepts and persists the submission
- optionally replaying WAL records through the legacy compatibility publish path when compatibility publish is
  explicitly enabled
- consuming `matching.executions` and mapping them back to outbound FIX responses

The runtime QuickFIX session config defaults to `config/quickfix/acceptor.cfg`, which now uses
`../../config/quickfix/fix-spec/FIX44.xml` as the shared FIX dictionary.

Session-aware deployment baseline:

- Stateful deployments should use stable owner ids that match the pod identity, such as `quickfix-gateway-0` and
  `quickfix-gateway-1`
- the repo now includes Kubernetes scaffolding for a headless StatefulSet plus owner-pinned Services in `deploy/k8s/`
- client session routing should target the owner service name, for example `quickfix-gateway-owner-0:5001`
- startup recovery completes before the QuickFIX acceptor starts, and readiness only flips up after recovery succeeds

## Implemented Behavior

Current repo state is no longer a bootstrap-only scaffold. The Java gateway already includes:

- QuickFix/J acceptor lifecycle wiring and session logging
- config binding through `PlatformProperties` and `QuickFixGatewayProperties`
- `NewOrderSingle (35=D)` ingestion
- local WAL append-and-flush before the first accept/reject decision is emitted
- synchronous gRPC submission to `risk-service`
- bounded retry and breaker protection around `risk-service` submission
- `ExecutionReport (PendingNew)` only after `risk-service` persistence succeeds
- `ExecutionReport (Rejected)` / `OrderCancelReject` when `risk-service` rejects or is unavailable
- deterministic `OrderID = O-<ClOrdID>` generation
- best-effort Kafka publish path kept only as transitional compatibility wiring and now disabled by default
- WAL replay through the same transitional compatibility path and now disabled by default unless compatibility publish
  is explicitly enabled
- consume path for `matching.executions` and outbound FIX mapping
- cancel request ingestion support beyond the historical C++ baseline

Two intentional Java-specific differences from the historical C++ gateway baseline are documented and tested:

- WAL is stored as one-line JSON records rather than the older plain-text format
- baseline `PendingNew` uses `ExecID = E-<recordId>` so outbound acknowledgements stay traceable to persisted WAL state

## Configuration

Configuration is resolved only through Spring Config Data. Use canonical Spring properties or relaxed environment names
such as
`SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID`; JSON config discovery and legacy
`SIMPLEMATCH_FIX_*` aliases are not supported.

Important Spring properties:

- `simplematch.quickfix-gateway.acceptor-enabled`: start or skip the QuickFix/J acceptor
- `simplematch.quickfix-gateway.data-plane-enabled`: enable or skip `matching.executions` consume wiring
- `simplematch.quickfix-gateway.compatibility-publish-enabled`: enable legacy Kafka `orders.commands` publish and WAL
  replay compatibility path; default is off
- `simplematch.quickfix-gateway.replay-enabled`: replay WAL records through the legacy compatibility publish path on
  startup; only takes effect when compatibility publish is enabled
- `simplematch.quickfix-gateway.owner-id`: stable logical gateway owner identity; the default Kafka consumer group for
  `matching.executions` now follows this owner id instead of using one shared gateway-wide group
- `simplematch.grpc.targets.risk-service`: gRPC target used for synchronous `risk-service` submission
- `simplematch.quickfix-gateway.risk-client.*`: deadline, bounded retry, and breaker settings for `risk-service` calls

Default paths from `QuickFixGatewayProperties`:

- QuickFIX config: `config/quickfix/acceptor.cfg`
- WAL path: `data/quickfix/wal/inbound.wal`
- owner id: `quickfix-gateway-0`

Session-aware scale-out baseline:

- the current implementation now exposes a stable `owner-id` config so deployments can pin a FIX session to one logical
  gateway owner
- the gateway's Kafka consumer group defaults to that owner id, which is the first step toward owner-aware outbound
  execution routing
- the service now exposes `/healthz`, `/healthz/liveness`, `/readyz`, and `/metrics` on the management HTTP port for
  Kubernetes probing and diagnostics
- startup recovery is now a dedicated lifecycle phase that runs before the QuickFIX acceptor starts; with the current
  code, WAL replay is only executed in that phase when the compatibility publish path and replay flag are both enabled
- the default repo QuickFIX acceptor config now uses continuity defaults (`ResetOnLogon/Logout/Disconnect=N`) for
  same-owner restart semantics
- this does not yet implement standby promotion, fencing, or route transfer; those remain follow-up work captured in
  `docs/quickfix-gateway-session-scale-plan.md`

## Run

Start the service normally:

```bash
./gradlew :services:quickfix-gateway:bootRun
```

Start it without opening the FIX acceptor socket:

```bash
./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false'
```

Start it with the acceptor disabled and the `matching.executions` consume path also disabled for a dry run:

```bash
./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false --simplematch.quickfix-gateway.data-plane-enabled=false --simplematch.quickfix-gateway.replay-enabled=false'
```

If you need to re-enable the legacy compatibility publish/replay path for migration or diagnostics, enable it
explicitly:

```bash
./gradlew :services:quickfix-gateway:bootRun --args='--simplematch.quickfix-gateway.compatibility-publish-enabled=true --simplematch.quickfix-gateway.replay-enabled=true'
```

## Verification

Run the full module test suite:

```bash
./gradlew :services:quickfix-gateway:test
```

Run the dedicated QuickFIX/J certification-style simulator evidence:

```bash
./gradlew :services:quickfix-gateway:certificationTest
```

The certification-style test proves the repo-local baseline path end to end:

- FIX logon succeeds
- `35=D` is accepted
- WAL is persisted
- synchronous risk submission is accepted
- `ExecutionReport (PendingNew)` is returned only after that acceptance
- FIX logout completes cleanly

## Scope Boundary

This README describes the current Java target gateway in this repository.

- It is already beyond the original bootstrap/session baseline in several areas.
- It does not claim venue-specific external certification.
- It is the target runtime architecture for the FIX boundary in this repo.
- The remaining Kafka publish/replay pieces in this module are transitional compatibility hooks, disabled by default,
  and not the documented long-term ingress path.
