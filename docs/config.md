# Configuration Runbook (env + JSON)

The canonical target configuration ownership and safety rules are in
[the target platform specification](../services/docs/platform/configuration.md).
This page records current keys, aliases, defaults, and operational endpoints;
it is not a second target-configuration source.

This repo uses **environment variables** plus an **optional JSON config file**.

## Precedence

1. CLI flags (when supported by a service)
2. Environment variables
3. JSON config file
4. Service defaults

## Config file discovery

Services may load a JSON file from:

- `SIMPLEMATCH_CONFIG` (absolute or relative path), else
- `config/simplematch.json` if present (recommended for local dev), else
- no file (env + defaults only)

## Common keys (Task 0)

Top-level keys in `config/simplematch*.json`:

- `env`: `dev` | `stage` | `prod`
- `kafka.brokers`: e.g. `localhost:9092`
- `kafka.topics.*`: topic names
- `kafka.partitions.*`: integer partition counts (catalog / desired)
- `postgres.dsn`: Postgres DSN
- `redis.endpoints`: string array
- `grpc.targets.*`: gRPC target strings
- `routing.snapshotPath`: published routing snapshot JSON path
- `observability.otel.exporterOtlpEndpoint`: OTLP endpoint
- `observability.prometheus.port`: metrics port

Debezium / Kafka Connect publication is external infrastructure, so there is no longer an in-app `riskService.outboxRelay.*` config block in `risk-service`.

Database migrations are managed through the Gradle Flyway convention tasks rather than a runtime config flag.

## Routing snapshot

JSON:

- `routing.snapshotPath`: published routing snapshot file path. The default is `classpath:routing/orders-validated.snapshot.json`, and local or production deployments should override it with a shared external file such as `config/routing/orders-validated.snapshot.json`.

Minimum snapshot shape:

```json
{
 "entries": [
  {
   "symbol": "AAPL",
   "routingBucket": "mega-cap-tech",
   "kafkaPartitionId": 7
  }
 ]
}
```

At startup, `risk-service` reads this snapshot. If a `symbol` is not listed, it falls back to the stable partition `floorMod(symbol.hashCode(), simplematch.kafka.partitions.ordersValidated)`.

Debezium / Kafka Connect 仍屬外部基礎設施，但 repo 已提供第一版 connector 範本：

- `deploy/compose/risk-service-outbox-connector.json`
- `deploy/k8s/risk-service-outbox-connector-configmap.yaml`

這兩份範本都會把 outbox row 的 `kafka_partition_id` 透過 Debezium Outbox Event Router SMT 映射成 Kafka record 的 explicit partition。

## quickfix-gateway

JSON:

- `quickfixGateway.quickfixConfigPath`: path to QuickFIX acceptor config (`.cfg`)
- `quickfixGateway.walPath`: inbound WAL path
- `quickfixGateway.ownerId`: stable logical gateway owner id used for session-aware deployment and owner-specific consumer group defaults

Canonical environment variable overrides:

- `SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG`
- `SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH`
- `SIMPLEMATCH_QUICKFIX_GATEWAY_OWNER_ID`

Legacy environment variable overrides retained for compatibility:

- `SIMPLEMATCH_ENV`
- `SIMPLEMATCH_FIX_QUICKFIX_CONFIG`
- `SIMPLEMATCH_FIX_WAL_PATH`

Legacy aliases are deprecated compatibility paths. New config should use `quickfixGateway`, `simplematch.quickfix-gateway.*`, and `SIMPLEMATCH_QUICKFIX_GATEWAY_*`.

Phase 1 session-aware scale-out baseline:

- `simplematch.quickfix-gateway.owner-id` defaults to `quickfix-gateway-0`
- `quickfix-gateway` now defaults its Kafka `matching.executions` consumer group id to the configured owner id rather than the shared application name
- this is only the owner identity skeleton for same-owner restart; it is not yet a full standby failover implementation

Operational endpoints:

- `quickfix-gateway` now exposes `/healthz`, `/healthz/liveness`, `/readyz`, and `/metrics` on the management HTTP port
- `/readyz` stays out of service until startup recovery finishes
- when `simplematch.quickfix-gateway.acceptor-enabled=true`, readiness also requires the QuickFIX acceptor lifecycle to be running

QuickFIX continuity defaults:

- the repo default `config/quickfix/acceptor.cfg` now uses `ResetOnLogon=N`, `ResetOnLogout=N`, and `ResetOnDisconnect=N`
- Kubernetes continuity deployments should mount owner-local persistent storage for QuickFIX file store, file log, and WAL paths
