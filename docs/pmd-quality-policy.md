# PMD quality policy

`./gradlew -q staticAnalysis` runs PMD 7.24.0 for every production Java module. All blocking
analyzers inspect only each module's handwritten `src/main/java` tree; test and generated protobuf
sources are intentionally outside this policy. The generated XML and HTML reports live under
`build/reports/pmd/` and are uploaded by the Java CI job.

`config/pmd/simplematch-design.xml` is the canonical 47-rule PMD policy. `pmdMain` loads every
`<rule ref>` declared in that file; Gradle defaults are disabled so no abbreviated or implicit
ruleset is added. The policy currently spans PMD design, best-practices, code-style, error-prone,
multithreading, and performance categories. Its rules and thresholds are frozen: agents must not
create, modify, rename, or delete PMD ruleset files. A desired policy change requires a separately
approved architectural decision rather than an implementation edit.

The former `config/pmd/completed-parameter-safety.xml` was temporary migration debt, not a second
policy source, and has been removed. The replacement `parameterSafetyMain` gate uses Checkstyle's
`ParameterNumber` from an in-memory blocking Gradle task, with a maximum of seven defined in build
logic. It must not change `simplematch-design.xml`, modify the main Checkstyle configuration, or
introduce a new checked-in ruleset. The gate currently covers the completed Account Authority and
Risk Admission/outbox slices; extending that scope requires its own refactoring slice.

PMD, Checkstyle, and SpotBugs are blocking for every intended handwritten production Java module.
Error Prone currently reports configured checks as warnings. Its ratchet is separate: first reach
zero Error Prone warnings, then make findings blocking and add executable policy coverage. PMD
dependencies are version-locked in every Java module. For the protobuf-contracts module, SpotBugs
analyses only its handwritten `contracts.v2` classes; generated protobuf and gRPC classes remain
available solely for dependency resolution.

## Finding disposition

The initial inventory was resolved by centralizing PostgreSQL URI parsing in
`PostgresJdbcUrl`, extracting platform and v2 new-order validation modules, and enabling the policy
for handwritten protobuf contracts. PMD's remaining per-symbol `@SuppressWarnings` entries are
narrow compatibility, wire, transaction, or defensive-copy seams tracked separately by issue #21;
they are not a baseline and must be removed when their owning migration or adapter retirement lands.
New production code must not copy an existing exception merely to satisfy a rule.

SpotBugs mutable-exposure review is tracked by issue #31. The former class-wide
`EI_EXPOSE_REP`/`EI_EXPOSE_REP2` exclusions were removed. The only retained entries in
`config/spotbugs/exclude.xml` are six field-scoped `EI_EXPOSE_REP2` matches for private-final
infrastructure collaborators: the JDBC template, repository/transaction ports, Kafka template,
and WAL appender. These references are required Spring/JDBC/Kafka/WAL protocol collaborators,
have no accessors or payload-returning boundary, and therefore cannot expose mutable domain data.
Each entry records its owning area, the no-external-exposure rationale, and the retirement
condition (an immutable executor/port). Focused ownership tests cover account lifecycle payload
copies, risk outbox payload copies, and fresh WAL replay collections.

Do not add a package-wide suppression, a broad baseline, or a category-wide PMD ruleset. A new
exception must be attached to the smallest class or method that owns the behavior and include a
reason plus its retirement condition. SpotBugs exclusions follow the same owner-and-retirement
rule and must remain field- or symbol-scoped.
