# Testing

This is the canonical target specification for SimpleMatch's verification layers.

## Verification layers

- Unit tests cover isolated matching, risk, validation, identity, recovery-state, and encoding rules.
- Integration tests cover service wiring, persistence, messaging, identity propagation, and protocol
  compatibility where real dependencies are needed for confidence.
- Certification and smoke tests exercise externally visible service entry points, including the FIX
  gateway when it is in scope.
- Documentation navigation checks verify the reader-visible target-document paths independently of
  Java, native, database, or service runtime checks.

## Recovery and boundary verification

Changes to admission or recovery behavior prove crash and ambiguity handling, not only happy-path
success. Relevant tests cover the durable `WAL -> UNKNOWN -> Risk` ordering, every recovery-sidecar
state, `PENDING` without resubmission, reconciliation of terminal outcomes, and reuse of the original
`command_id` when `UNKNOWN + NOT_FOUND` permits resubmission.

Identity changes prove ownership across the real service boundary: canonical Account UUID enters
through FIX or gRPC validation, is propagated unchanged through Risk, reaches the Account domain,
and is persisted as the same identity. Gateway-derived internal order identity is tested separately
from the FIX-facing `OrderID` contract.

Error-boundary tests prove both audiences. Operator diagnostics retain actionable internal context,
while client-visible messages do not leak internal service names, RPC topology, breaker state,
database details, stack traces, or implementation-specific transport reasons. See
[Consistency, Recovery, Identity, and Error Boundaries](consistency-recovery-identity-and-errors.md)
for the canonical invariants.

## Scope discipline

Each change runs the narrowest relevant check first, followed by the affected module or service
suite. Java, native, and Flyway validation remain required when those respective implementation
surfaces change; documentation-only work uses its Markdown navigation and formatting seams instead.

Current commands, CI execution records, phase gates, and certification evidence are execution-state
material. They intentionally remain outside this target specification so the target verification
model does not claim a particular run has happened.
