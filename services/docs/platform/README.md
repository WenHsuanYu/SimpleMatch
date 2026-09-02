# Target Platform

These documents define the intended cross-cutting platform. They distinguish target operating rules
from repository execution plans, phase gates, task tracking, and certification evidence.

- [Data model](data-model.md) — target records, projections, identity ownership, and authority.
- [Database architecture](database-architecture.md) — database topology and schema ownership.
- [Consistency, recovery, identity, and error boundaries](consistency-recovery-identity-and-errors.md)
  — synchronous admission semantics, WAL/sidecar recovery, reconciliation, canonical identity, and
  operator-versus-client error-message rules.
- [Configuration](configuration.md) — configuration ownership and boundaries.
- [Development environment](development-environment.md) — local toolchain, repository layout, and
  build expectations.
- [Deployment](deployment.md) — local composition, Kubernetes deployment, and service discovery.
- [Matching fleet recovery](matching-fleet-recovery.md) — partition ownership, Lease fencing, and
  the normal no-force-delete recovery procedure.
- [Gateway admission operations](gateway-admission-operations.md) — five-state admission control,
  operator commands, audit records, and operational-status boundaries.
- [Query-service projection replay](query-service-rebuild.md) — authenticated reset, dual consumer
  group replay, Redis rebuild, and convergence evidence.
- [Observability](observability.md) — traces, logs, metrics, dashboards, and alerts.
- [Testing](testing.md) — layered verification and certification posture.
- [Troubleshooting](troubleshooting.md) — first diagnostic checks for common operating failures.
