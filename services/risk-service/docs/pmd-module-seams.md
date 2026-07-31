# Risk service PMD module seams

Risk admission keeps decision ordering in `OrderAdmissionValidator` and
`SubmissionValidator`. Those public seams own the business result; their extracted assemblers,
rejection policy, and decision factory are package-private implementation details. No admission or
submission validator retains a PMD suppression for method count or complexity.

The remaining PMD suppressions are compatibility or immutable-value seams, not validation policy.
They are intentionally narrow and must be removed when their named retirement condition is met.

| Type | Rule | Reason | Retirement condition |
| --- | --- | --- | --- |
| `SubmissionResult` flat constructors | `ExcessiveParameterList` | In-repository test fixtures still construct durable outcomes from positional values; production JDBC adapters use the canonical constructor. | Remove the constructors when the remaining fixtures compose result values directly. |
| `SubmissionCommand`, `RequestMetadata`, `OrderDetails` | `TooManyMethods` | v1 ingress requires both typed domain accessors and wire-compatible accessors while migration is in progress. | Remove the compatibility accessors when ingress consumes the grouped values directly. |
| `OutboxRecord` | `TooManyMethods` | The immutable row value provides defensive-copy access and compatibility accessors for persistence adapters. | Remove the flat accessors when repository adapters consume `EventInfo`, `Routing`, `PayloadEnvelope`, and `AggregateRef` directly. |
| `RiskServiceConfiguration` | `TooManyMethods` | One Spring composition root wires the admission and submission flows that share the same data source and transaction manager. | Split the composition root when admission and submission are independently deployed or configured. |

`@Transactional` remains owned by the public application services. The JDBC repositories,
validation policy, and outbox factories participate in those transactions rather than opening
their own boundaries.
