# Risk Admission aggregate root

Status: accepted

Risk Admission models one Admission aggregate root per command identity. The root owns normalized
decision facts and the alternate FIX business key for idempotency. It also owns the lifecycle from
PENDING to ACCEPTED or REJECTED. Equivalent retries reproduce the stored outcome. Admission rejects
conflicting identities.

Account reservations, matching orders, and outbox delivery remain separate concerns in their
owning contexts. The admission application service coordinates local finalization and recovers
remote reservation work without a distributed transaction.

Three application modules divide this work:

- `OrderAdmissionApplicationService` owns synchronous admission orchestration.
- `AdmissionLifecycleTransactions` owns pending and terminal local transactions.
- `PendingAdmissionRecovery` owns scheduled retries.

Repositories remain thin adapters. Account reservation RPCs remain outside database transactions.
Terminal journal state and its outbox record remain one atomic local outcome.
