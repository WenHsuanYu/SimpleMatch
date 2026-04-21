# quickfix-gateway

Java + Spring Boot + QuickFix/J based FIX 4.4 gateway.

This service is the target external FIX entrypoint in the current SimpleMatch architecture. It sits in front of the event-driven trading pipeline, while `matching-engine` remains the native C++ matching core.

## Role In The System

`quickfix-gateway` is responsible for:

- accepting FIX 4.4 sessions through QuickFix/J
- converting inbound FIX order flow into domain commands
- appending inbound traffic to the local WAL before attempting the first business-level FIX acknowledgement
- synchronously submitting new and cancel commands to `risk-service` over gRPC
- returning `ExecutionReport (PendingNew)` only after `risk-service` accepts and persists the submission
- optionally replaying WAL records through the legacy compatibility publish path when replay is enabled
- consuming `matching.executions` and mapping them back to outbound FIX responses

The runtime QuickFIX session config defaults to `config/fix/acceptor.cfg`, which now uses `fix-spec/FIX44.xml` as the shared FIX dictionary.

## Implemented Behavior

Current repo state is no longer a bootstrap-only scaffold. The Java gateway already includes:

- QuickFix/J acceptor lifecycle wiring and session logging
- config binding through `SimpleMatchConfig` and Spring `@ConfigurationProperties`
- legacy config override compatibility for app-config, QuickFIX config, WAL path, and environment
- `NewOrderSingle (35=D)` ingestion
- local WAL append-and-flush before the first accept/reject decision is emitted
- synchronous gRPC submission to `risk-service`
- bounded retry and breaker protection around `risk-service` submission
- `ExecutionReport (PendingNew)` only after `risk-service` persistence succeeds
- `ExecutionReport (Rejected)` / `OrderCancelReject` when `risk-service` rejects or is unavailable
- deterministic `OrderID = O-<ClOrdID>` generation
- best-effort Kafka publish path kept only as transitional compatibility wiring
- WAL replay through the same transitional compatibility path
- consume path for `matching.executions` and outbound FIX mapping
- cancel request ingestion support beyond the historical C++ baseline

Two intentional Java-specific differences from the historical C++ gateway baseline are documented and tested:

- WAL is stored as one-line JSON records rather than the older plain-text format
- baseline `PendingNew` uses `ExecID = E-<recordId>` so outbound acknowledgements stay traceable to persisted WAL state

## Configuration

The service keeps the legacy override inputs used by the earlier migration work:

- `--app-config`
- `--quickfix-config`
- `--wal`
- `SIMPLEMATCH_CONFIG`
- `SIMPLEMATCH_ENV`
- `SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG`
- `SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH`
- `SIMPLEMATCH_FIX_QUICKFIX_CONFIG`
- `SIMPLEMATCH_FIX_WAL_PATH`

Important Spring properties:

- `simplematch.quickfix-gateway.acceptor-enabled`: start or skip the QuickFix/J acceptor
- `simplematch.quickfix-gateway.data-plane-enabled`: enable Kafka compatibility publish plus `matching.executions` consume wiring
- `simplematch.quickfix-gateway.replay-enabled`: replay WAL records through the legacy compatibility publish path on startup
- `simplematch.grpc.targets.risk-service`: gRPC target used for synchronous `risk-service` submission
- `simplematch.quickfix-gateway.risk-client.*`: deadline, bounded retry, and breaker settings for `risk-service` calls

Compatibility notes:

- JSON config now prefers `quickfixGateway`, while the old `fixGateway` key is still accepted.
- Spring property names now prefer `simplematch.quickfix-gateway.*`, while the old `simplematch.fix-gateway.*` names are still aliased.
- The old `SIMPLEMATCH_FIX_*` environment variables are still accepted as legacy aliases.
- Deprecated aliases currently emit warnings when they are used so remaining downstream configs can be migrated before full removal.

Default paths from the shared config library:

- QuickFIX config: `config/fix/acceptor.cfg`
- WAL path: `data/fix/wal/inbound.wal`

## Run

Start the service normally:

```bash
./gradlew :services:quickfix-gateway:bootRun
```

Start it without opening the FIX acceptor socket:

```bash
./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false'
```

Start it with the acceptor disabled and Kafka/replay disabled for a dry run:

```bash
./gradlew :services:quickfix-gateway:bootRun --args='--spring.main.web-application-type=none --simplematch.quickfix-gateway.acceptor-enabled=false --simplematch.quickfix-gateway.data-plane-enabled=false --simplematch.quickfix-gateway.replay-enabled=false'
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
- The remaining Kafka publish/replay pieces in this module are transitional compatibility hooks, not the documented long-term ingress path.
