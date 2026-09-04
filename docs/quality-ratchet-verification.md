# Four-analyzer quality-ratchet verification

Verified on 2026-08-03 after the #21/#22 closure slice. The preceding
four-analyzer baseline was recorded in commit `94fdaa356d4ea28e6492c82f336717f1d206c214`.

## Reproducible evidence

The ordinary root gate passed without diagnostic continue mode:

```bash
./gradlew --no-daemon -q staticAnalysis
```

A fresh execution regenerated every analyzer report and also passed without
`--continue`:

```bash
./gradlew --no-daemon staticAnalysis --rerun-tasks
```

The fresh run executed 60 actionable tasks and included the blocking
`compileJava`, `checkstyleMain`, `pmdMain`, and `spotbugsMain` tasks for their
configured production modules. Subsequent runs no longer register the retired
Checkstyle parameter-safety task.

The analyzer report inventory after the fresh run was:

| Analyzer | Intended production modules | Active report findings |
| --- | ---: | ---: |
| Checkstyle | 7 | 0 |
| PMD | 7 | 0 |
| SpotBugs | 7 | 0 |

Error Prone does not emit a separate analyzer report. Its blocking behavior
is part of each handwritten-source `compileJava` task, and
`ErrorPronePolicyTest` proves that a `MissingOverride` finding fails the
ordinary `staticAnalysis` lifecycle. Generated-source compilation remains
outside that gate.

The seven intended modules at the 2026-08-03 historical baseline were:

- `services/account-service`
- `services/marketdata-publisher`
- `services/persistence`
- `services/quickfix-gateway`
- `services/risk-service`
- `shared-java/simplematch-config`
- `shared-java/simplematch-contracts`

That baseline predates #119's retirement of the runtime Market Reference publisher. The current
quality gate therefore covers the six retained Java modules and does not reference a deleted
publisher project.

The full Java suite and QuickFIX certification also passed:

```bash
./gradlew --no-daemon test :services:quickfix-gateway:certificationTest
```

## Policy boundaries

- `config/pmd/simplematch-design.xml` contains 47 explicit rule references
  and remains the single PMD ruleset source of truth.
- PMD's existing `ExcessiveParameterList` rule is the sole automated
  parameter-count gate and retains its default threshold of ten. Checkstyle
  has no parameter-count configuration.
- Checkstyle, PMD, and SpotBugs inspect handwritten `src/main/java`; test
  and generated protobuf sources remain outside the production policy.
- Error Prone is blocking for handwritten `compileJava` and remains excluded
  from generated-source compilation.

## Parent issue closure assessment

This record separates technical evidence from GitHub state; the issue status
updates follow the implementation commit.

### #21 — PMD ratchet

The current evidence satisfies the technical criteria described in #21:

- The five originally selected PMD rules are included in the blocking
  47-rule policy for all seven intended modules.
- No production-source `@SuppressWarnings` or `//NOPMD` entry was found. The
  former compatibility publisher used `@CanIgnoreReturnValue` before the
  retired Gateway publication path was deleted; no such exception remains.
- PMD reports, dependency locks, policy documentation, and the CI blocking
  lifecycle are present.
- The ordinary static-analysis gate and Java test suite pass.

Conclusion: #21's technical closure criteria are satisfied. Its GitHub status
is updated separately after this implementation commit.

### #22 — blocking static-analysis quality gate

The documented #22 closure conditions have this evidence status:

| Condition | Status | Evidence |
| --- | --- | --- |
| One blocking root lifecycle with the canonical 47-rule PMD policy | Satisfied | Root gate passed; `PmdPolicyTest` passed in the build-logic test suite. |
| Active Checkstyle, PMD, and SpotBugs findings repaired without a baseline | Satisfied | Seven Checkstyle, seven PMD, and seven SpotBugs XML reports contain zero active findings. |
| PMD suppressions and broad analyzer exclusions governed | Satisfied | No production PMD suppression remains; SpotBugs has six field-scoped entries with owner, rationale, and retirement condition. |
| Remaining Checkstyle suppressions reviewed and dispositioned | Satisfied | The five Spring Boot entry-point suppressions were removed by private constructors; only the generated-source boundary remains. |
| Generated/test source boundaries preserved | Satisfied | Quality plugin scope and Error Prone policy test preserve those boundaries. |
| Full Java suite, QuickFIX certification, and graph refresh | Satisfied | Both test commands passed; `graphify update .` rebuilt the repository graph. |

Conclusion: #22's technical closure criteria are satisfied. Its GitHub status
is updated separately after this implementation commit.
