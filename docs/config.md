# Config (env + JSON)

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
- `observability.otel.exporterOtlpEndpoint`: OTLP endpoint
- `observability.prometheus.port`: metrics port

Debezium / Kafka Connect publication is external infrastructure, so there is no longer an in-app `riskService.outboxRelay.*` config block in `risk-service`.

Database migrations are managed through the Gradle Flyway convention tasks rather than a runtime config flag.

## quickfix-gateway

JSON:

- `quickfixGateway.quickfixConfigPath`: path to QuickFIX acceptor config (`.cfg`)
- `quickfixGateway.walPath`: inbound WAL path

Canonical environment variable overrides:

- `SIMPLEMATCH_QUICKFIX_GATEWAY_QUICKFIX_CONFIG`
- `SIMPLEMATCH_QUICKFIX_GATEWAY_WAL_PATH`

Legacy environment variable overrides retained for compatibility:

- `SIMPLEMATCH_ENV`
- `SIMPLEMATCH_FIX_QUICKFIX_CONFIG`
- `SIMPLEMATCH_FIX_WAL_PATH`

Legacy aliases are deprecated compatibility paths. New config should use `quickfixGateway`, `simplematch.quickfix-gateway.*`, and `SIMPLEMATCH_QUICKFIX_GATEWAY_*`.

