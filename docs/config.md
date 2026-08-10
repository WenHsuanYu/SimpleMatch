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
Account Authority consumes `GrpcProperties` for its account-service target and `PostgresProperties`
for its datasource, so its runtime and persistence wiring depends only on the required capabilities.
Risk Admission consumes `GrpcProperties`, `KafkaProperties`, and `PostgresProperties` for its
account client, policy-aware outbox, runtime, and datasource. The Phase 1 target takes its routing
identity and explicit partition from the approved startup Market Reference Artifact; the current
legacy projection remains a removal target until #126 installs that loader.
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
`simplematch.postgres.dsn` must come from the Secret in those environments. Secrets are externally
provisioned; no Secret value, DSN credential, token, or password is committed to this repository.
`deploy/k8s/quickfix-gateway-configuration-rbac.yaml` is the reference RBAC shape, and
`deploy/k8s/simplematch-platform-configmap.yaml`
contains only non-sensitive data.

The risk-service Debezium connector template resolves its database username and password from Kafka
Connect environment variables. Those environment variables must be injected from a Kubernetes
Secret.

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
its wire contract lacks the venue and authoritative policy identity. v2 policy-aware Admission is
the production ingress; legacy pending v2 Admissions still recover from their persisted partition
and nullable policy identity during the compatibility window.
