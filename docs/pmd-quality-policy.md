# PMD quality policy

`./gradlew -q staticAnalysis` runs PMD 7.24.0 for every production Java module. All blocking
analyzers inspect only each module's handwritten `src/main/java` tree; test and generated protobuf
sources are intentionally outside this policy. The generated XML and HTML reports live under
`build/reports/pmd/` and are uploaded by the Java CI job.

`config/pmd/simplematch-design.xml` is the canonical 47-rule PMD policy. `pmdMain` loads every
`<rule ref>` declared in that file; Gradle defaults are disabled so no abbreviated or implicit
ruleset is added. The policy currently spans PMD design, best-practices, code-style, error-prone,
multithreading, and performance categories. Do not duplicate the complete rule inventory in prose:
add, remove, or configure a rule in the XML, then update `PmdPolicy`'s expected count and verify
that the declared references are unique.

PMD, Checkstyle, and SpotBugs are blocking for every intended handwritten production Java module.
Error Prone reports configured checks as warnings during this adoption phase. PMD dependencies are
version-locked in every Java module. For the protobuf-contracts module, SpotBugs analyses only its
handwritten `contracts.v2` classes; generated protobuf and gRPC classes remain available solely for
dependency resolution.

## Finding disposition

The initial inventory was resolved by centralizing PostgreSQL URI parsing in
`PostgresJdbcUrl`, extracting platform and v2 new-order validation modules, and enabling the policy
for handwritten protobuf contracts. Remaining per-symbol `@SuppressWarnings` entries are narrow
compatibility, wire, transaction, or defensive-copy seams tracked by issue #21; they are not a
baseline and must be removed when their owning migration or adapter retirement lands. New
production code must not copy an existing exception merely to satisfy a rule.

Do not add a package-wide suppression, a broad baseline, or a category-wide PMD ruleset. A new
exception must be attached to the smallest class or method that owns the behavior and include a
reason plus its retirement condition. Follow-up removal work remains tracked in issue #21.
