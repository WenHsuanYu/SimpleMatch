# PMD quality-policy rollout

Status: accepted; amended 2026-08-03. Adopt PMD 7.24.0 because it is the highest version officially
supported by the current Gradle 9.6.1 wrapper. Analyse production Java only, using the explicitly
enumerated repository ruleset in `config/pmd/simplematch-design.xml`; PMD defaults are disabled.
Every `<rule ref>` in that XML is blocking through each module's `pmdMain` task and the repository
`staticAnalysis` lifecycle. Broad baselines are prohibited; compatibility exceptions must be narrow,
documented, and tracked. This separates stable quality-policy adoption from a future Gradle upgrade
and avoids hiding the codebase-wide refactor debt.

## Amendment

The initial rollout began with five design rules. The policy now adopts the complete 47-rule XML
inventory rather than maintaining a second abbreviated list in Kotlin or Markdown. The XML is the
single source of truth; its rule references and expected count are verified by `PmdPolicy` tests,
while Gradle passes the same file directly to PMD.

## Single-ruleset consolidation amendment

`config/pmd/simplematch-design.xml` retains its existing 47 rules and their existing configuration,
including PMD's default `ExcessiveParameterList` threshold. Agents must not create, modify, rename,
or delete PMD ruleset files. The temporary `config/pmd/completed-parameter-safety.xml` file and the
later Checkstyle-backed `parameterSafetyMain` task were retired. PMD is now the sole automated
parameter-count gate; Checkstyle has no `ParameterNumber` configuration or dedicated
parameter-safety task.

Checkstyle, PMD, SpotBugs, and Error Prone are blocking. Error Prone completed its two-stage ratchet:
the existing warnings were removed, then `allErrorsAsWarnings` was removed and
`ErrorPronePolicyTest` proved that a finding fails the ordinary build lifecycle while generated
source handling remains unchanged. The 2026-08-03 closure slice completed Issue #22's Checkstyle
suppression review by adding private constructors to the five Spring Boot entry points; the
suppression file now retains only the generated-source boundary. It also removed the
`FutureReturnValueIgnored` source suppression by making the compatibility publisher's
fire-and-forget return contract explicit with `@CanIgnoreReturnValue`. The technical criteria for
Issues #21 and #22 are now satisfied; their GitHub statuses are updated after the implementation
commit. Error Prone cleanup and blocking adoption belong to this specification rather than
expanding either existing issue retroactively.
