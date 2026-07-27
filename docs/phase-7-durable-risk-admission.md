# Phase 7: Durable risk admission

Phase 7 is implemented as a recoverable local saga in `risk-service`.

- `OrderAdmissionValidator` converts v2 commands into typed, transport-neutral
  admission data and applies Taiwan session, identity, and fixed-point rules.
- `admission_journal` records `PENDING`, `ACCEPTED`, or `REJECTED` before any
  account RPC. The RPC is made outside a database transaction.
- Final journal state and the complete binary accepted/rejected outbox event are
  committed atomically. `recoverPending` retries bounded, stale pending rows.
- `CdcLagBackpressurePolicy` provides a deterministic admission stop when the
  durable CDC metric exceeds its configured bound; production wiring reads the
  owner-schema `cdc_delivery_lag` metric and applies a two-second account RPC
  deadline.
- `risk_v2.proto` exposes the deep admission gRPC contract on the same server
  lifecycle as v1. The v1 submission
  path is wrapped by `V1AdmissionCompatibilityAdapter` while migration is in
  progress.

`OrderAdmissionApplicationServiceTransactionTest` covers pending-before-call,
accepted and rejected finalization, equivalent replay, stable conflict, and
remote outage recovery. `CdcLagBackpressurePolicyTest` covers the lag gate.
