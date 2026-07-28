# Phase 6: Account reservation authority

Phase 6 is implemented in `account-service` as a local, database-authoritative transaction boundary.

- `AccountReservationApplicationService` owns reserve, release, fill, query, and controlled provisioning operations.
- `JdbcAccountAuthorityRepository` uses row locks with optimistic versions;
  `account_service.inbox` makes execution delivery idempotent and records optional aggregate sequence claims.
- Reservation decisions and lifecycle changes write binary account outbox events in the same transaction as the
  authority mutation.
- `AccountReservationApplicationServiceTransactionTest` covers accepted and rejected cash reservations, position
  reservations, duplicate request and execution delivery, release idempotence, and concurrent cash reservations.

The existing v1 gRPC surface remains available through the reservation service adapter. PostgreSQL-specific isolation
and lock-plan verification is a separate deployment-level check; the migration and repository SQL are shared by H2 and
PostgreSQL.
