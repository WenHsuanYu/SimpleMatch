# Four-analyzer quality-ratchet verification

Verified on 2026-08-03 against the source and configuration tree at
`683a886decae5f59ed3187098fc029169675e2d4`.

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
`compileJava`, `checkstyleMain`, `pmdMain`, `spotbugsMain`, and
`parameterSafetyMain` tasks for their configured production modules.

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

The seven intended modules are:

- `services/account-service`
- `services/marketdata-publisher`
- `services/persistence`
- `services/quickfix-gateway`
- `services/risk-service`
- `shared-java/simplematch-config`
- `shared-java/simplematch-contracts`

The full Java suite and QuickFIX certification also passed:

```bash
./gradlew --no-daemon test :services:quickfix-gateway:certificationTest
```

## Policy boundaries

- `config/pmd/simplematch-design.xml` contains 47 explicit rule references
  and remains the single PMD ruleset source of truth.
- The completed parameter-safety ratchet uses the in-memory Checkstyle
  `ParameterNumber` configuration in `ParameterCountPolicy` with a maximum
  of seven parameters. It does not create or modify a checked-in PMD or
  Checkstyle ruleset.
- Checkstyle, PMD, and SpotBugs inspect handwritten `src/main/java`; test
  and generated protobuf sources remain outside the production policy.
- Error Prone is blocking for handwritten `compileJava` and remains excluded
  from generated-source compilation.

## Parent issue closure assessment

This verification intentionally does not change the GitHub state or body of
#21 or #22.

### #21 — PMD ratchet

The current evidence satisfies the technical criteria described in #21:

- The five originally selected PMD rules are included in the blocking
  47-rule policy for all seven intended modules.
- No production-source `@SuppressWarnings("PMD...")` or `//NOPMD` entry was
  found. The sole source suppression is Error Prone's
  `FutureReturnValueIgnored`, so it is not a PMD exception.
- PMD reports, dependency locks, policy documentation, and the CI blocking
  lifecycle are present.
- The ordinary static-analysis gate and Java test suite pass.

Conclusion: the current evidence supports #21 closure review, but it is not a
GitHub status change. The #21 owner must reconcile the evidence with the issue
and make the separate status decision.

### #22 — blocking static-analysis quality gate

The documented #22 closure conditions have this evidence status:

| Condition | Status | Evidence |
| --- | --- | --- |
| One blocking root lifecycle with the canonical 47-rule PMD policy | Satisfied | Root gate passed; `PmdPolicyTest` passed in the build-logic test suite. |
| Active Checkstyle, PMD, and SpotBugs findings repaired without a baseline | Satisfied | Seven Checkstyle, seven PMD, and seven SpotBugs XML reports contain zero active findings. |
| PMD suppressions and broad analyzer exclusions governed | Satisfied | No production PMD suppression remains; SpotBugs has six field-scoped entries with owner, rationale, and retirement condition. |
| Remaining Checkstyle suppressions reviewed and dispositioned | Unverified | Five narrow Spring Boot entry-point suppressions and one generated-source boundary remain; this verification does not assign their #22 owner disposition. |
| Generated/test source boundaries preserved | Satisfied | Quality plugin scope and Error Prone policy test preserve those boundaries. |
| Full Java suite, QuickFIX certification, and graph refresh | Satisfied | Both test commands passed; `graphify update .` rebuilt the repository graph. |

Conclusion: #22 is not fully closure-ready from this evidence because the
remaining Checkstyle suppression review is unverified. #22 remains open and
unchanged so its owner can reconcile the historical inventory and decide its
status explicitly.
