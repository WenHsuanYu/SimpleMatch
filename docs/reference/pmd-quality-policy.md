# PMD quality policy

`./gradlew -q staticAnalysis` runs PMD 7.24.0 for every production Java module. All blocking
analyzers inspect only each module's handwritten `src/main/java` tree; test and generated protobuf
sources are intentionally outside this policy. The generated XML and HTML reports live under
`build/reports/pmd/` and are uploaded by the Java CI job.

`config/pmd/simplematch-design.xml` is the sole canonical PMD policy. `pmdMain` loads every
`<rule ref>` declared in that file; Gradle defaults are disabled so no abbreviated or implicit
ruleset is added. The policy spans PMD design, best-practices, code-style, error-prone,
multithreading, and performance categories. It had 48 rules after the 2026-11-16
`MutableStaticState` amendment, but **its rule count is not frozen**. The XML, not a Kotlin
constant or a duplicated list, determines the active rules.

Before **each** rule addition, removal, replacement, or property, priority, threshold, or
exclusion change, the responsible human developer must explicitly approve that exact
change. Record the approver, date, approved changes, rationale, expected findings and
verification plan in the owning Issue or ADR before editing. A past approval, an agent's
recommendation, or passing tests do not grant permission for another change. Do not create
a second PMD ruleset or import implicit categories. Current policy tests validate the
ruleset's structure without requiring a particular total number of rules.
The Java CI now explicitly runs the included-build `PmdPolicyTest` after
`staticAnalysis`. This structural test rejects foreign-namespace rules and
requires the canonical PMD root namespace without fixing the number of rules.

The former `config/pmd/completed-parameter-safety.xml` was temporary migration debt, not a second
policy source, and has been removed. PMD's existing `ExcessiveParameterList` rule is the sole
automated parameter-count gate and retains PMD's default threshold of ten. Checkstyle has no
parameter-count configuration or dedicated parameter-safety task. This keeps the approval-controlled PMD XML
as the single source of truth and does not introduce a second checked-in or in-memory ruleset.

PMD, Checkstyle, SpotBugs, and Error Prone are blocking for every intended handwritten production
Java module. Error Prone's warning backlog was cleared before `allErrorsAsWarnings` was removed;
`build-logic/src/test/kotlin/com/simplematch/gradle/ErrorPronePolicyTest.kt` now proves that a
handwritten `MissingOverride` finding fails the `staticAnalysis` lifecycle through `compileJava`
while generated-source boundaries remain outside the gate. PMD dependencies are version-locked
in every Java module. For the protobuf-contracts module, SpotBugs analyses only its handwritten
`contracts.v2` classes; generated protobuf and gRPC classes remain
available solely for dependency resolution.

The `MutableStaticState` rule prohibits non-private, non-final static fields in production
Java. It is a narrow source-level encapsulation check, not a general concurrency proof.
Private mutable static fields and a final reference to a mutable object remain outside its
predicate. The first change was verified through an isolated PMD baseline and red/green
source fixtures, with the exact `MutableStaticState` report confirming the failure.
For a separately developer-approved rollback, remove only the affected rule, record the
reason in the ADR, and rerun the structural policy test and Java quality gates. Do not
restore a fixed numeric expectation, weaken another rule, or conceal the finding with a
broad suppression.

## Finding disposition

The initial inventory was resolved by centralizing PostgreSQL URI parsing in
`PostgresJdbcUrl`, extracting platform and v2 new-order validation modules, and enabling the policy
for handwritten protobuf contracts. As of the 2026-08-03 quality-ratchet verification, production
source contains no `@SuppressWarnings` or `//NOPMD` entries. The former compatibility-publisher
exception was removed together with the retired Gateway publication path; new production code must
not add an exception merely to satisfy a rule.

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
