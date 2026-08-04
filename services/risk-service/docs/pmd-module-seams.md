# Risk service PMD module seams

Risk admission keeps decision ordering in `OrderAdmissionValidator` and
`SubmissionValidator`. Those public seams own the business result; their extracted assemblers,
rejection policy, and decision factory are package-private implementation details. No admission or
submission validator retains a PMD suppression for method count or complexity.

The Spring composition root is split by responsibility: shared runtime/JDBC/transaction
infrastructure is configured separately from Admission and Submission collaborators. Bean names and
the application-owned transaction template remain unchanged. `OutboxRecord` exposes event, routing,
payload, and aggregate groups directly to the JDBC adapter; only that adapter flattens them into the
unchanged outbox row.

The remaining compatibility seams are not validation or outbox policy. They are intentionally narrow
and must be removed when their named retirement condition is met. `SubmissionResult` already uses
its five-value semantic record constructor and has no flat-constructor PMD suppression. The
`SubmissionCommand`, `RequestMetadata`, and `OrderDetails` compatibility model no longer needs a
`TooManyMethods` suppression: request identity, FIX identity, and order details are grouped into
typed records, while the legacy v1 wire constructors only normalize their external string shape.
The v1 submission-ingress owner is responsible for retiring those constructors when all v1 callers
compose the grouped values directly and repository search confirms that no wire or fixture caller
remains.

| Type | Rule | Reason | Retirement condition |
| --- | --- | --- | --- |
| Grouped `SubmissionCommand` compatibility model | None | Typed request identity, FIX identity, and order-detail records keep the submission model cohesive. The seven-field `RequestMetadata` and six-field `OrderDetails` constructors are bounded legacy v1 wire-normalization factories. | The v1 submission-ingress owner removes those factories after every v1 caller composes grouped values directly and round-trip tests remain green. |

`OrderAdmissionApplicationService` owns synchronous validation, backpressure, and remote account
reservation orchestration. `AdmissionLifecycleTransactions` is the deep application module for
the two local transaction seams: it commits pending journal state, and it commits terminal journal
state with its outbox record atomically. The module receives the bounded `TransactionTemplate`,
while JDBC repositories, validation policy, and outbox factories remain adapters and policies that
do not open their own business transactions.
