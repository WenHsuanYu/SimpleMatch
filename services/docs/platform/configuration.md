# Configuration

This is the canonical target specification for SimpleMatch configuration boundaries. It defines how
configuration participates in the architecture; it does not enumerate every deployed value or claim
that all loading paths are already uniform.

## Configuration role

Configuration is an operational control-plane concern. It can select endpoints, credentials, limits,
routing snapshots, observability destinations, and feature policy, but it must not become a runtime
dependency in the deterministic matching loop or a hidden way to change an accepted business
outcome.

The system has three configuration scopes:

| Scope                   | Owner                                           | Examples                                                                                 |
|-------------------------|-------------------------------------------------|------------------------------------------------------------------------------------------|
| Shared platform         | `PlatformProperties` and deployment environment | Kafka, PostgreSQL, Redis, gRPC, routing, observability, currency, and time-zone settings |
| Service-owned runtime   | The owning service                              | Listener addresses, dependency deadlines, concurrency, and feature policy                |
| External infrastructure | Deployment and platform tooling                 | Kafka Connect, database credentials, Kubernetes resources, and secrets                   |

Each setting has one documented owner and one clear application point. A shared setting is not a
licence for a service to mutate another service's policy.

## Resolution and safety rules

The implementation uses the Spring Environment and Config Data. Test and command-line properties
override environment variables; those override Kubernetes imports; profile YAML overrides base YAML
and typed safe defaults. A deployment must be able to identify the final source of a value without
inspecting multiple competing defaults.

- Secrets are supplied by Kubernetes Secrets, never committed in YAML, ConfigMaps, fixtures, or
  emitted in logs. ConfigMaps and Secrets have disjoint property-key ownership.
- A config change that affects wire compatibility, event ordering, persistence, or matching
  behaviour follows the relevant contract or release process; it is not an ad-hoc runtime toggle.
- Dependency endpoints, retry limits, and timeouts have bounded values and fail validation at
  startup when absent or invalid.
- Routing snapshots are versioned input to admission. A reload is atomic and observable; an accepted
  command keeps the routing decision made for it.
- Database schema evolution is managed by Flyway, not a runtime configuration flag.

## Change propagation

Control-plane updates are versioned and observable. Current staging and production settings require
a controlled rolling restart; no runtime refresh may partially apply a multi-setting policy.

## Target versus execution state

This page is the target authority for configuration ownership and safety. The
[configuration runbook](../../../docs/config.md) lists current key names, precedence, secret
ownership, connector templates, and operational endpoints.
