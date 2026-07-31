# Risk service PMD module seams

Risk admission keeps decision ordering in `OrderAdmissionValidator` and
`SubmissionValidator`. Those public seams own the business result; their extracted assemblers,
rejection policy, and decision factory are package-private implementation details. No admission or
submission validator retains a PMD suppression for method count or complexity.

The remaining PMD suppressions are compatibility or immutable-value seams, not validation policy.
They are intentionally narrow and must be removed when their named retirement condition is met.

| Type | Rule | Reason | Retirement condition |
| --- | --- | --- | --- |
| `AdmissionCommand` flat constructor | `ExcessiveParameterList` | Journal recovery still calls the positional v1-compatible constructor. | Remove the constructor when `V1AdmissionCompatibilityAdapter` and all positional callers use the composed values. |
| `SubmissionResult` flat constructors | `ExcessiveParameterList` | JDBC and older fixtures still construct durable outcomes from positional values. | Remove the constructors when `V1AdmissionCompatibilityAdapter`, JDBC adapters, and fixtures use the composed result values. |
| `SubmissionCommand`, `RequestMetadata`, `OrderDetails` | `TooManyMethods` | v1 ingress requires both typed domain accessors and wire-compatible accessors while migration is in progress. | Remove the compatibility accessors when ingress consumes the grouped values directly. |
| `OutboxRecord` | `TooManyMethods` | The immutable row value provides defensive-copy access and compatibility accessors for persistence adapters. | Remove the flat accessors when repository adapters consume `EventInfo`, `Routing`, `PayloadEnvelope`, and `AggregateRef` directly. |
| `RiskServiceConfiguration` | `TooManyMethods` | One Spring composition root wires the admission and submission flows that share the same data source and transaction manager. | Split the composition root when admission and submission are independently deployed or configured. |

`@Transactional` remains owned by the public application services. The JDBC repositories,
validation policy, and outbox factories participate in those transactions rather than opening
their own boundaries.
