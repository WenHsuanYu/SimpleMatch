# Phase 7: Durable risk admission

Phase 7 is implemented as a recoverable local saga in `risk-service` and is the authoritative
business-outcome boundary for order admission.

The domain boundary is one Admission aggregate root per `command_id`. It owns normalized decision
facts, FIX business-key idempotency, and the durable `PENDING` to `ACCEPTED` or `REJECTED`
lifecycle. Account reservations and matching orders remain separate context-owned roots; the saga
coordinates the Account outcome without extending a database transaction across the service
boundary.

- QuickFIX Gateway submits through the production v2 `OrderAdmissionService`; the former
  transitional v1 submission seam is not the authoritative Gateway admission path.
- `OrderAdmissionValidator` converts v2 commands into typed, transport-neutral admission data and
  applies Taiwan session, identity, and fixed-point rules.
- `admission_journal` records `PENDING` before any Account RPC. `PENDING` proves durable Risk
  ownership and is never permission for Gateway recovery to resubmit the command.
- The Account RPC occurs outside a Risk database transaction and reuses the admission `command_id` as
  the reservation idempotency request identity.
- Final journal state and the complete binary accepted/rejected outbox event are committed
  atomically. Risk returns the terminal admission result only after this durable local outcome.
- Pending recovery retries bounded, stale Risk-owned `PENDING` work; Gateway recovery does not
  compete by blindly resubmitting `PENDING` commands.
- `GetAdmissionOutcome(command_id)` exposes the authoritative reconciliation view as `NOT_FOUND`,
  `PENDING`, `ACCEPTED`, or `REJECTED`.
- `CdcLagBackpressurePolicy` provides a deterministic admission stop when the durable CDC metric
  exceeds its configured bound; production wiring reads owner-schema CDC delivery lag and applies a
  bounded Account RPC deadline.

A missing gRPC response is not an authoritative Risk rejection. Gateway transport failures remain
`UNKNOWN` until reconciliation can determine the durable Risk outcome. The complete outcome,
reconciliation, retry, and client/operator error-message policy is defined in
[Consistency, Recovery, Identity, and Error Boundaries](../services/docs/platform/consistency-recovery-identity-and-errors.md).

The Account reservation v2 live verification sequence, including the direct RPC boundary, pending
recovery scenarios, Kubernetes prerequisites, and the later Kafka/Debezium extension, is documented
in [Account Reservation v2 Live Verification](account-reservation-v2-live-verification.md).

Canonical Account identity is a UUID owned by Account Service. Risk validates/preserves the value
carried by the v2 command and forwards the same identity to Account reservation; Risk does not
translate account identifiers.

`OrderAdmissionApplicationServiceTransactionTest` covers pending-before-call, accepted and rejected
finalization, equivalent replay, stable conflict, and remote outage recovery. Reconciliation gRPC
coverage proves the four durable outcome states, while Gateway recovery tests prove that only a
safe `UNKNOWN + NOT_FOUND` combination may resubmit the original `command_id`.
