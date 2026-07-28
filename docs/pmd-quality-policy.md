# PMD quality policy

`./gradlew -q staticAnalysis` runs PMD 7.24.0 for every production Java module. PMD analyses only each module's
handwritten `src/main/java` tree; test and generated protobuf sources are intentionally outside this policy. The
generated XML and HTML reports live under `build/reports/pmd/` and are uploaded by the Java CI job.

The checked-in ruleset contains exactly these PMD design rules:

- `ExcessiveParameterList`
- `CyclomaticComplexity`
- `NcssCount`
- `ExcessivePublicCount`
- `TooManyMethods`

PMD is blocking. Checkstyle, Error Prone, and SpotBugs retain their independent blocking behavior for service and shared
Java modules. The protobuf-contracts plugin is the documented exception: it historically ran conventions only, so it
enables PMD while leaving Checkstyle/SpotBugs disabled for generated-contract support code. PMD dependencies are
version-locked in every Java module.

## Finding disposition

The initial inventory was resolved by centralizing PostgreSQL URI parsing in
`PostgresJdbcUrl`, extracting platform and v2 new-order validation modules, and enabling the policy for handwritten
protobuf contracts. Remaining per-symbol
`@SuppressWarnings` entries are narrow compatibility seams tracked by this issue; they are not a baseline and must be
removed when the owning protocol migration or adapter retirement lands. The two flat `SubmissionResult` constructors are
marked deprecated compatibility adapters and are the only parameter-list exceptions; all other exceptions document a
bounded wire, transaction, or defensive-copy seam and must not be copied to new code.

Do not add a package-wide suppression, a broad baseline, or a category-wide PMD ruleset. A new exception must be
attached to the smallest class or method that owns the behavior and include a reason plus its retirement condition.
Follow-up removal work remains tracked in issue #21.
