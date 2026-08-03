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
or delete PMD ruleset files. The only approved consolidation mutation was removal of the temporary
`config/pmd/completed-parameter-safety.xml` file after its stricter seven-parameter policy moved to
the blocking `parameterSafetyMain` Gradle task backed by Checkstyle's `ParameterNumber`. That task
defines the maximum in build logic without modifying the repository's main Checkstyle configuration
or introducing another checked-in ruleset.

Checkstyle, PMD, SpotBugs, and Error Prone are blocking. Error Prone completed its two-stage ratchet:
the existing warnings were removed, then `allErrorsAsWarnings` was removed and
`ErrorPronePolicyTest` proved that a finding fails the ordinary build lifecycle while generated
source handling remains unchanged. Issue #22 owns single-ruleset consolidation, the seven-parameter
replacement gate, and review of remaining Checkstyle suppressions. The 2026-08-03 verification
found no PMD suppressions in production source, so the PMD-suppression criterion in #21 has current
technical evidence; #21's GitHub status remains an issue-owner decision. Error Prone cleanup and
blocking adoption belong to a separate specification rather than expanding either existing issue
retroactively.
