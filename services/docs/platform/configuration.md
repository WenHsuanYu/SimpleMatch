# Configuration

This is the canonical target specification for SimpleMatch configuration
boundaries. It defines how configuration participates in the architecture; it
does not enumerate every deployed value or claim that all loading paths are
already uniform.

## Configuration role

Configuration is an operational control-plane concern. It can select endpoints,
credentials, limits, routing snapshots, observability destinations, and feature
policy, but it must not become a runtime dependency in the deterministic
matching loop or a hidden way to change an accepted business outcome.

The system has three configuration scopes:

| Scope | Owner | Examples |
| --- | --- | --- |
| Shared platform | `SimpleMatchConfig` and deployment environment | Kafka, PostgreSQL, Redis, gRPC, routing, and observability settings |
| Service-owned runtime | The owning service | Listener addresses, dependency deadlines, concurrency, and feature policy |
| External infrastructure | Deployment and platform tooling | Kafka Connect, database credentials, Kubernetes resources, and secrets |

Each setting has one documented owner and one clear application point. A shared
setting is not a licence for a service to mutate another service's policy.

## Resolution and safety rules

The target precedence is explicit command-line override, environment override,
configuration file, then safe service default. A deployment must be able to
identify the final source of a value without inspecting multiple competing
defaults.

- Secrets are supplied by the deployment secret mechanism, never committed in
  JSON configuration or emitted in logs.
- A config change that affects wire compatibility, event ordering, persistence,
  or matching behaviour follows the relevant contract or release process; it
  is not an ad-hoc runtime toggle.
- Dependency endpoints, retry limits, and timeouts have bounded values and
  fail validation at startup when absent or invalid.
- Routing snapshots are versioned input to admission. A reload is atomic and
  observable; an accepted command keeps the routing decision made for it.
- Database schema evolution is managed by Flyway, not a runtime configuration
  flag.

## Change propagation

Control-plane updates are versioned and observable. Services either apply a
validated configuration atomically at a documented refresh boundary or require
a restart. No refresh may partially apply a multi-setting policy, and each
service documents whether a setting is startup-only, refreshable, or immutable
for the lifetime of a process.

## Target versus execution state

This page is the target authority for configuration ownership and safety. The
[configuration runbook](../../../docs/config.md) lists current key names,
legacy aliases, connector templates, and operational endpoints.
