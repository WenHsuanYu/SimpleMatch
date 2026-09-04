# Configuration Runbook

Spring's `Environment` is the only SimpleMatch runtime configuration authority. The retired
`SIMPLEMATCH_CONFIG` JSON file, `config/simplematch.json`, and the legacy
`simplematch.fix-gateway.*` aliases are not read.

## Environments

Exactly one environment profile is active: `local`, `test`, `staging`, or
`production`. Kubernetes is a deployment platform, never a Spring profile. Every service defaults to
`local`; deployments select another profile with
`SPRING_PROFILES_ACTIVE`.

The profile and `simplematch.environment` must agree. The same canonical property names bind in
every profile. The base
`application.yaml` supplies safe, non-secret defaults and the profile file supplies only the
environment identity. Default Taiwan-market configuration is `TWD` and `Asia/Taipei`.

## Precedence

For one property, the effective order is:

1. Test-only properties and command-line arguments.
2. Environment variables, including canonical relaxed names such as
   `SIMPLEMATCH_KAFKA_BROKERS`.
3. Kubernetes ConfigMap and Secret Config Data imports when Kubernetes support is enabled.
4. Profile-specific `application-{profile}.yaml`.
5. Base `application.yaml` and typed-property defaults.

Spring's normal Config Data rules apply within each source. Do not create two different deployment
sources for the same key: the startup validator rejects any overlap between Kubernetes ConfigMap and
Secret keys.

## Typed Ownership

`EnvironmentProperties`, `KafkaProperties`, `PostgresProperties`, `RedisProperties`,
`GrpcProperties`, `ObservabilityProperties`, and `MarketProperties` are the
independently bindable capability owners under the existing `simplematch.*` namespace. The former
shared root facade has been removed; these modules preserve the existing property keys and
defaults.
`QuickFixGatewayFileProperties` owns gateway paths, `QuickFixGatewayRuntimeProperties` owns owner
identity and feature flags, and `QuickFixGatewayRiskClientProperties` owns risk-client policy under
the unchanged `simplematch.quickfix-gateway.*` namespace.
Query Service owns the read-side PostgreSQL schema and consumes final Matching and Account lifecycle
facts asynchronously; its versioned HTTP reads use Redis only as a rebuildable cache with durable
PostgreSQL fallback.
Account Authority consumes `GrpcProperties` for its account-service target and `PostgresProperties`
for its datasource, so its runtime and persistence wiring depends only on the required capabilities.
Account's datasource is created by the shared Boot auto-configuration from the canonical
`simplematch.postgres.dsn`; Account supplies only its `account_service` schema and pool policy.
The `spring.datasource.*` namespace is not a competing source for this service, and Flyway remains
owned by the service's explicit migration configuration rather than datasource startup.
The shared PostgreSQL URL adapter preserves TLS query parameters such as `sslmode=verify-full` and
`sslrootcert` when a PostgreSQL URI is used. Account and Risk gRPC servers and the Risk v2 client
default to local plaintext but require complete certificate, private-key, and trust-certificate
paths before enabling mTLS in staging or production.
Risk Admission consumes `GrpcProperties`, `KafkaProperties`, and `PostgresProperties` for its
account client, policy-aware outbox, runtime, and datasource. The completed #126 cutover loads the
approved startup Market Reference Artifact, persists its identity and explicit partition with the
Admission, and removes production Risk fallback to runtime Market Reference topics or hashing.
QuickFIX Gateway consumes `EnvironmentProperties` for its runtime identity, `GrpcProperties` for the
risk-service channel, and `KafkaProperties` for the compatibility topic; gateway-local paths,
features, and retry policy remain owned by its service-specific property modules.
The offline Market Reference builder is not a Spring runtime and has no `simplematch.*` runtime
configuration namespace. Its source, artifact, and approval command contract is documented in
[the Market Reference approval workflow](market-reference-approval-workflow.md).

Useful canonical keys include:

- `simplematch.kafka.brokers`
- `simplematch.kafka.topics.*`
- `simplematch.postgres.dsn`
- `simplematch.grpc.targets.*`
- `simplematch.market.currency` (`TWD`)
- `simplematch.market.time-zone` (`Asia/Taipei`)
- `simplematch.quickfix-gateway.owner-id`
- `simplematch.quickfix-gateway.quickfix-config-path`
- `simplematch.quickfix-gateway.wal-path`
- `simplematch.risk-service.scheduling-enabled` (defaults to `true`; set to `false` only when
  background admission recovery must be disabled, such as a narrow context test)
- `simplematch.risk-service.cdc-delivery.enabled` (defaults to `false`; the local Kubernetes
  profile enables the Risk-owned Kafka delivery observer)
- `simplematch.risk-service.cdc-delivery.fixture-records-allowed` (defaults to `false`; only the
  disposable local Kubernetes certification overlay enables this to acknowledge the explicitly
  marked native Matching fixture records; never enable it in staging or production)
- `simplematch.risk-service.cdc-delivery.consumer-group` (defaults to `risk-cdc-delivery`; the
  durable Kafka group whose committed offsets gate metric refresh)
- `simplematch.risk-service.cdc-delivery.refresh-interval` (defaults to `5s`; controls bounded
  durable lag refresh scheduling)
- `simplematch.risk-service.cdc-delivery.query-timeout` (defaults to `5s`, range `1ms` through
  `10s`; bounds Kafka Admin progress queries)
  The `1ms` floor prevents a positive sub-millisecond duration from truncating to an immediate
  timeout; it is not a latency SLO, so deployments should retain the `5s` default unless a shorter
  budget is justified by measured environment latency.
- `simplematch.risk-service.admission.maximum-metric-age` (defaults to `30s`; stale CDC evidence
  fails closed for new Risk admission)
- `simplematch.query-service.rebuild.http-enabled` (defaults to `false`; enables only the
  authenticated operator reset seam)
- `simplematch.query-service.rebuild.operator-token` (required when the reset seam is enabled;
  never commit or persist the token)
- `simplematch.query-service.redis.command-timeout` (defaults to `2s`; maximum `10s`; bounds one
  Redis command before the durable PostgreSQL fallback runs)
- `simplematch.query-service.redis.connect-timeout` (defaults to `500ms`; maximum `10s`; bounds
  establishing a Redis connection)

The query-service rebuild adapter is disabled by default. A bounded local or CI certification run
may enable it with `SIMPLEMATCH_QUERY_SERVICE_REBUILD_HTTP_ENABLED=true` and supply the transient
`SIMPLEMATCH_QUERY_SERVICE_REBUILD_OPERATOR_TOKEN`; both variables are Spring `Environment`
overrides, and the token must be generated and removed by the run rather than stored in a manifest.

The operator replay reset owns an eight-second PostgreSQL transaction timeout; a timeout rolls
back the durable reset and the HTTP operation fails closed.

The query-service outage probe accepts `SIMPLEMATCH_QUERY_ISOLATION_PROBE_SECONDS` (maximum 30
complete samples) and `SIMPLEMATCH_QUERY_ISOLATION_COMMAND_TIMEOUT_SECONDS` (maximum 30) to bound
its repeated Kubernetes observations. The probe value is a nominal one-second sampling
interval/count rather than a hard wall-clock deadline; Kubernetes fault handling, Kafka committed
offset inspection, and evidence materialization may make the recorded elapsed time longer. Each
external command remains individually bounded and every requested sample remains fail-closed.
After that quiescent window, the certification runner releases one
public FIX IOC order while query-service is scaled to zero and requires correlated Risk,
Matching, Persistence, Account, QuickFIX, and market-data evidence before it reports active
processing liveness.

## Kubernetes

Each Spring service defaults to `optional:kubernetes:` through Config Data for local use. A
Kubernetes workload sets
`SIMPLEMATCH_KUBERNETES_CONFIG_IMPORT=kubernetes:` and
`SIMPLEMATCH_KUBERNETES_ENABLED=true`, so an unavailable required source fails startup instead of
being silently skipped. The configured sources are
`simplematch-platform-config`, `{service}-config`, and `{service}-secrets`. The client uses named
reads and least-privilege RBAC.

`staging` and `production` fail startup unless a Kubernetes ConfigMap and a Kubernetes Secret are
both present.
`simplematch.postgres.dsn` must come from the Secret in those environments. The external service
Secret uses the `postgres_dsn` key, which the workload maps through `SIMPLEMATCH_POSTGRES_DSN` to
that canonical property. Startup compares the effective resolved property with the Secret value
exactly, so a higher-precedence environment override cannot replace the Secret transport contract.
Secrets are externally provisioned; no Secret value, DSN credential, token, or password is committed
to this repository.
`deploy/k8s/quickfix-gateway-configuration-rbac.yaml` is the reference RBAC shape, and
`deploy/k8s/simplematch-platform-configmap.yaml`
contains only non-sensitive data.
The complete cross-service base/overlay contract and external Secret keys are documented in
[`deploy/k8s/README.md`](../deploy/k8s/README.md#cross-service-base-and-overlays), with a local
rendering gate in `scripts/test-kubernetes-overlays.sh`.

The Risk and Account Debezium connector templates resolve their database usernames and passwords
from Kafka Connect environment variables. Those environment variables must be injected from a
Kubernetes Secret, and the local production-like certification registers both owner-scoped
connectors before declaring the Java workloads ready.

## Local CDC verification harness

The following environment variables belong only to the local/CI CDC verification harness. They are
not Spring `Environment` properties and do not create a second runtime configuration authority for
Risk or Account services:

- `SIMPLEMATCH_CONNECT_OFFSET_FLUSH_INTERVAL_MS` controls Kafka Connect's worker offset-flush
  interval in `deploy/compose/kafka-connect.local.yml`. The Compose default is `60000` ms. The live
  CDC fault script defaults it to `120000` ms so it can observe a publication and terminate Connect
  before the next source-offset flush; callers may override it for diagnostic runs.
- `SIMPLEMATCH_CDC_COMPOSE_PROJECT` optionally supplies the run-owned Docker Compose project name for
  `scripts/run-outbox-cdc-contract-check.sh`. If unset, the script generates a unique name from the
  GitHub run identity (when present), wall-clock epoch, and process ID. An explicitly selected name
  must not already own Compose resources; collision is a preflight failure, never a cleanup signal.
- `SIMPLEMATCH_CDC_OBSERVER_TIMEOUT_SECONDS` is the single end-to-end deadline for the Kubernetes
  Risk CDC outage/recovery observer, including rollout, port-forward, polling, and bounded cleanup.
  The observer reserves 30 seconds of that budget for connector recovery and diagnostics (default
  `180`, minimum `31`, maximum `600`).
The Kubernetes CDC observer reads the effective `maximum-metric-age` from the deployed
`risk-service-config` ConfigMap rather than accepting a second age setting. A baseline older than
that runtime bound, or updated in the future, fails closed before the connector is paused.

## Change Policy

Configuration is startup-only in staging and production. Apply a validated ConfigMap or Secret
change, then perform a controlled rolling restart. Do not enable automatic refresh for admission,
routing, session, or transport policy. Local and test changes take effect on process restart.

## Routing Policy

The Phase 1 target is one externally checksummed final `market_reference.json` for each trading day.
The offline builder retains approved output under `config/market-reference/approved/YYYY-MM-DD/` and
generates either an immutable ConfigMap or a digest-pinned OCI data-image delivery fragment. Risk
and Matching will load the same mounted file at startup; #126 and #127 own that runtime integration.
There is no target Market Reference Kafka topic, outbox, runtime API, or Spring configuration key.

The source-compatible v1 submission adapter is not registered as a production Spring service while
its wire contract lacks the venue and authoritative artifact route. The v2 artifact-backed
Admission path is the only production ingress; every accepted route persists its artifact identity,
routing algorithm version, and explicit partition. There is no nullable policy-identity compatibility
window in the current pre-release schema.
