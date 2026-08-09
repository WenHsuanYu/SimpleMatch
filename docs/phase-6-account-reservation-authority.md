# Phase 6: Account reservation authority

Phase 6 is implemented in `account-service` as a local, database-authoritative transaction boundary.

- Account Service owns canonical account identity through the UUID-backed `AccountId` domain value.
  FIX and upstream services propagate that identity unchanged; they do not derive aliases or create
  competing account identifiers.
- Account gRPC ingress validates canonical account UUIDs before application-service execution.
- `AccountReservationApplicationService` owns reserve, release, fill, query, and controlled
  provisioning operations.
- `AccountAuthorityReader` reads authoritative state and acquires row locks for the application
  transaction. `AccountAuthorityLifecycleWriter` records idempotency claims and persists lifecycle
  state with optimistic versions; their JDBC adapters share only row mapping and database-dialect
  mechanics.
- Account limits, positions, and reservations persist `account_id` as the same native PostgreSQL UUID
  carried through the Account domain and service boundary.
- `account_service.inbox` makes execution delivery idempotent and records optional aggregate sequence
  claims.
- Reservation decisions and lifecycle changes write binary account outbox events in the same
  transaction as the authority mutation.
- `AccountReservationApplicationServiceTransactionTest` covers accepted and rejected cash
  reservations, position reservations, duplicate request and execution delivery, release
  idempotence, and concurrent cash reservations.

Risk reservation uses the original admission `command_id` as the Account reservation `request_id`,
so retry and recovery preserve the same idempotency identity instead of creating a second
reservation attempt. The authoritative cross-service retry, identity, and error-boundary rules are
in
[Consistency, Recovery, Identity, and Error Boundaries](../services/docs/platform/consistency-recovery-identity-and-errors.md).

Malformed account UUIDs are client/boundary validation failures, not transport uncertainty. A future
human-readable external account code would require an explicit Account-owned resolution contract;
Gateway must not invent an account UUID by hashing or aliasing client text.

PostgreSQL-specific isolation and lock-plan verification remain deployment-level checks; migration
and repository SQL share the same Account-owned model across supported test/runtime databases.
