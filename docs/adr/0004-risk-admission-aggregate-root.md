# Risk Admission aggregate root

Status: accepted

Risk Admission models one Admission aggregate root per command identity. The root owns the
normalized decision facts, the alternate FIX business key used for idempotency, and the lifecycle
from PENDING to ACCEPTED or REJECTED; equivalent retries reproduce the stored outcome while
conflicting identities are rejected. Account reservations, matching orders, and outbox delivery
remain separate context-owned concerns, so the admission application service coordinates a local
finalization boundary and recovers remote reservation work without a distributed transaction.
`OrderAdmissionApplicationService` owns synchronous admission orchestration,
`AdmissionLifecycleTransactions` owns pending and terminal local transaction boundaries, and
`PendingAdmissionRecovery` owns scheduled retry orchestration. These are application modules:
repositories remain thin adapters, account reservation RPCs remain outside database transactions,
and terminal journal state plus its outbox record remain one atomic local outcome.
