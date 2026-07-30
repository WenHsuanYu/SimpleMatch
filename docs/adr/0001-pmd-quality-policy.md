# PMD quality-policy rollout

Status: accepted; amended 2026-07-31. Adopt PMD 7.24.0 because it is the highest version officially
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
