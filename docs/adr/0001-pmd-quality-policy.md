# PMD quality-policy rollout

Status: accepted. Adopt PMD 7.24.0 because it is the highest version officially supported by the current Gradle 9.6.1
wrapper. Analyse production Java only, using an explicitly enumerated repository ruleset and PMD defaults. The five-rule
design policy is blocking through each module's `pmdMain` task and the repository
`staticAnalysis` lifecycle. Broad baselines are prohibited; compatibility exceptions must be narrow, documented, and
tracked. This separates stable quality-policy adoption from a future Gradle upgrade and avoids hiding the codebase-wide
refactor debt.
