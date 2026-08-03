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

The remaining PMD suppressions are compatibility seams, not validation or outbox policy. They are
intentionally narrow and must be removed when their named retirement condition is met.

| Type | Rule | Reason | Retirement condition |
| --- | --- | --- | --- |
| `SubmissionResult` flat constructors | `ExcessiveParameterList` | In-repository test fixtures still construct durable outcomes from positional values; production JDBC adapters use the canonical constructor. | Remove the constructors when the remaining fixtures compose result values directly. |
| `SubmissionCommand`, `RequestMetadata`, `OrderDetails` | `TooManyMethods` | v1 ingress requires both typed domain accessors and wire-compatible accessors while migration is in progress. | Remove the compatibility accessors when ingress consumes the grouped values directly. |

`@Transactional` remains owned by the public application services. The JDBC repositories,
validation policy, and outbox factories participate in those transactions rather than opening
their own boundaries.
