# PMD static-analysis adoption research

**Status:** research only; no build or production configuration was changed.  
**Researched:** 2026-07-28.  
**Scope:** Gradle 9.6.1, Java 25 modules, and the existing `simplematch.java-quality` convention plugin.

## Decision summary

PMD **7.26.0** is the latest stable PMD release at the time of research, released on 2026-06-29. However, Gradle 9.6.1 officially supports PMD only through **7.24.0**. Do not describe PMD 7.26.0 as supported by the current wrapper. The safe implementation choice is therefore to pin **PMD 7.24.0** now, or to upgrade Gradle first and then re-check its published PMD compatibility range. PMD itself documents PMD 7 support with Gradle 8.6 or later, but Gradle owns the more specific tested-version range. [PMD release and Gradle integration](https://docs.pmd-code.org/latest/pmd_userdocs_tools_gradle.html) · [Gradle 9.6.1 PMD support matrix](https://docs.gradle.org/9.6.1/userguide/pmd_plugin.html)

This repository currently uses Java 25 and applies Checkstyle and SpotBugs through `simplematch.java-quality`; Error Prone is configured separately in `simplematch.java-conventions`. PMD should be added to the former as a fourth, complementary analysis layer, not as a replacement.

## Current repository fit

The affected build seam is `build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchJavaQualityPlugin.kt`. It already applies the core Checkstyle plugin and SpotBugs plugin, enables only production-source checks, writes XML/HTML reports, and connects those checks to root `staticAnalysis`.

The version catalog is the repository's pinned-version authority. The repo also uses dependency locking. Adding PMD therefore requires an intentional lock refresh and review of each affected Java module's `gradle.lockfile`; it must not leave resolution changes unreviewed.

The existing `.gitignore` ignores `build/` and `.gradle/`. Put PMD reports and any incremental cache under `build/`, so they remain generated and untracked. Commit the version catalog entry and the human-authored ruleset, not reports, caches, or generated inventories.

## Recommended Gradle design

Use Gradle's built-in `pmd` plugin; no third-party Gradle plugin is needed. It creates `pmdMain` and `pmdTest`, and `check` depends on both. It also provides `pmd` for the tool libraries and `pmdAux` for additional types needed during PMD type resolution. [Gradle PMD plugin](https://docs.gradle.org/9.6.1/userguide/pmd_plugin.html)

For consistency with the existing quality policy, initially analyse `src/main/java` only and disable `pmdTest`; that avoids introducing a source-quality policy for tests accidentally. Configure the root `staticAnalysis` task to depend on each module's `pmdMain`, rather than relying on callers to invoke `check`.

The implementation ticket should make these version-controlled changes:

1. Add `pmd = "7.24.0"` to `gradle/libs.versions.toml`.
2. Apply `pmd` in `SimpleMatchJavaQualityPlugin`, set `toolVersion` from the catalog, set `ignoreFailures = false`, attach XML and HTML reports, and wire `pmdMain` into `staticAnalysis`.
3. Add `config/pmd/simplematch-ruleset.xml` and configure `ruleSetFiles`/`ruleSetConfig`; clear `ruleSets` so that only the owned ruleset is active. PMD's Gradle guidance requires clearing `ruleSets` when using a custom-only ruleset. [PMD Gradle custom-ruleset guidance](https://docs.pmd-code.org/latest/pmd_userdocs_tools_gradle.html#custom-ruleset) · [Gradle PMD task DSL](https://docs.gradle.org/9.6.1/dsl/org.gradle.api.plugins.quality.Pmd.html)
4. Add `config/pmd/*` to the CI changed-area classifier and add `**/build/reports/pmd/**` to the uploaded Java artifacts.
5. Refresh and review dependency locks, update `docs/dependencies.md`, `docs/development-workflow.md`, and the static-analysis item in `tasks.md`.

Do **not** configure `targetJdk`: Gradle 9 deprecates it, says it has been a no-op for all supported PMD versions, and provides no replacement. PMD runs on the same Java used to run Gradle; the adoption spike must therefore verify Java-25 parsing against a representative module. [Gradle 9 PMD `targetJdk` deprecation](https://docs.gradle.org/9.6.1/userguide/upgrading_version_9.html#deprecated_pmd_target_jdk) · [Gradle PMD runtime note](https://docs.gradle.org/9.6.1/userguide/pmd_plugin.html)

Use one explicitly enumerated ruleset rather than category-wide references. PMD warns that a whole-category reference automatically enables newly added rules during a PMD upgrade; individual references keep the CI contract stable. [PMD ruleset authoring](https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html)

## Initial rules and ownership boundary

Start with a deliberately small, named design set and raise thresholds only after measuring the repository:

| Concern | PMD rule | Initial policy |
| --- | --- | --- |
| Long parameter lists | `category/java/design.xml/ExcessiveParameterList` | Inventory first, then choose a repository threshold. The PMD default is 10 parameters. |
| Branching complexity | `category/java/design.xml/CyclomaticComplexity` | Start at PMD's documented method/class defaults of 10/80 unless the inventory supports a stricter threshold. |
| Method/class statement volume | `category/java/design.xml/NcssCount` | Report only after a baseline; its defaults are method 60 and class 1500 NCSS. |
| Overlarge APIs | `category/java/design.xml/ExcessivePublicCount` and `TooManyMethods` | Triage as module-boundary work, not bulk mechanical cleanup. |
| Copy/paste blocks | CPD, separately scoped | Decide separately; it is not created as a Gradle core `pmdMain` task. |

PMD explains that long parameter lists often indicate a missing semantic grouping or excessive responsibility. Its listed remedies include builders, multiple parameter objects, overloads, and method decomposition. For this codebase, prefer a domain command/value object only when the fields form a coherent concept at the boundary; do not replace every parameter list with a generic `*Dto`. Public contracts need compatibility tests and a migration path. [ExcessiveParameterList](https://docs.pmd-code.org/latest/pmd_rules_java_design.html#excessiveparameterlist)

Complexity rules are signals for a smaller behavioural seam, not an instruction to scatter logic across utility methods. PMD's cyclomatic rule reports a method from 10 and notes that reported methods/classes should be broken into focused methods or subcomponents. [CyclomaticComplexity](https://docs.pmd-code.org/latest/pmd_rules_java_design.html#cyclomaticcomplexity) [NcssCount](https://docs.pmd-code.org/latest/pmd_rules_java_design.html#ncsscount) [TooManyMethods](https://docs.pmd-code.org/latest/pmd_rules_java_design.html#toomanymethods)

CPD is PMD's copy/paste detector. Its official documentation presents it as a CLI, Ant-task, or Maven-goal capability, while Gradle's core PMD plugin documents `pmdMain`/`pmdTest` only. Treat a CPD Gradle task as a separate design decision: it needs a reproducible invocation, an explicit token threshold, report location, and a decision about whether violations block CI. CPD should help remove duplicates, not institutionalise keeping duplicates in sync. [PMD CPD documentation](https://docs.pmd-code.org/latest/pmd_userdocs_cpd.html) · [Gradle PMD tasks](https://docs.gradle.org/9.6.1/userguide/pmd_plugin.html)

Keep responsibilities distinct:

- **Checkstyle:** formatting, naming, imports, and documentation conventions already owned by the repository.
- **Error Prone:** compiler-integrated correctness checks; keep the existing blocking configuration.
- **SpotBugs:** bytecode/dataflow bug patterns; retain its existing curated coverage and exclusions.
- **PMD:** source-level maintainability and design signals, especially parameter-object, complexity, and API-size candidates.
- **CPD:** duplicated token blocks, only if introduced as its own reproducible gate.

Avoid enabling overlapping PMD `errorprone` rules merely because the category name resembles Error Prone. Each overlapping rule needs an owner, reason, and suppression policy before it becomes blocking.

## Baseline, suppressions, and incremental analysis

The official Gradle PMD documentation describes rulesets, priorities, report configuration, `ignoreFailures`, and `maxFailures`; it does not document a generic generated baseline file. PMD instead documents rule configuration plus source/ruleset suppressions. Do not introduce a permanent, opaque "accept all existing violations" baseline.

Recommended rollout:

1. Run a non-blocking inventory against a candidate explicit ruleset and save its report only as a build artifact.
2. Split findings by bounded context and fix them in behaviour-preserving tickets, starting with public APIs and repeated parameter groups.
3. Enable one selected rule as blocking only after its in-scope modules are clean. Keep `ignoreFailures = false` for that selected policy.
4. If a temporary exclusion is necessary, keep it narrow, dated, owner-tagged, and tracked as removal debt. Do not use a broad module or package exclusion to mask a codebase-wide refactor.

PMD's own suppression order is: improve/configure the rule when appropriate; otherwise prefer a case-specific `@SuppressWarnings("PMD.RuleName")` or `//NOPMD` with a reason; use regex/XPath suppression only when necessary. PMD 7 also has an experimental `UnnecessaryWarningSuppression` rule that can later police stale suppressions. [PMD suppression guidance](https://docs.pmd-code.org/latest/pmd_userdocs_suppressing_warnings.html)

Incremental analysis can make local runs much faster while keeping the final report equivalent to a non-incremental run. Its cache is invalidated when the PMD version, ruleset, auxclasspath, or PMD execution classpath changes, and cache reuse across machines requires identical absolute paths. Keep it in ignored build output and do not make cross-machine cache sharing a correctness assumption. [PMD incremental analysis](https://docs.pmd-code.org/latest/pmd_userdocs_incremental_analysis.html) · [Gradle Pmd API](https://docs.gradle.org/9.6.1/dsl/org.gradle.api.plugins.quality.Pmd.html)

## Delivery slices and gates

1. **Compatibility spike:** add PMD 7.24.0 in one representative Java-25 module, explicit one-rule ruleset, XML/HTML reports, and verify `pmdMain`, `staticAnalysis`, and CI artifact output. Gate: Java 25 sources parse; the chosen version resolves under locked dependencies; no change to existing Error Prone/Checkstyle/SpotBugs behaviour.
2. **Platform integration:** move the tested configuration into `simplematch.java-quality`; add root aggregation, report upload, documentation, changed-path classification, and reviewed lockfiles. Gate: all existing Java modules execute PMD reproducibly with generated output ignored by Git.
3. **Design-policy inventory:** enable `ExcessiveParameterList` and selected complexity rules in report-only mode, classify each finding as domain command/value object, builder, overload, method decomposition, intentional boundary, or false positive. Gate: every finding has an owner and a test-preserving refactor plan.
4. **Bounded-context refactors:** complete small tickets per service/module, preserving protobuf/FIX/database/public Java contracts or making an explicit compatible migration. Gate per ticket: focused tests, `./gradlew -q staticAnalysis`, Javadocs for public/non-obvious Java members, and no new PMD violation in the touched scope.
5. **Blocking ratchet:** make selected rules blocking once their intended scope is clean; reduce any temporary exclusions to zero. Decide CPD only after its own task/threshold/report/CI semantics are specified. Gate: CI has a stable, explainable quality policy rather than a one-off repository cleanup.

## Source list

- [Gradle 9.6.1 PMD plugin user guide](https://docs.gradle.org/9.6.1/userguide/pmd_plugin.html)
- [Gradle 9.6.1 Pmd task DSL](https://docs.gradle.org/9.6.1/dsl/org.gradle.api.plugins.quality.Pmd.html)
- [Gradle 9 PMD `targetJdk` deprecation](https://docs.gradle.org/9.6.1/userguide/upgrading_version_9.html#deprecated_pmd_target_jdk)
- [PMD 7.26.0 Gradle integration page](https://docs.pmd-code.org/latest/pmd_userdocs_tools_gradle.html)
- [PMD Java design rules](https://docs.pmd-code.org/latest/pmd_rules_java_design.html)
- [PMD custom rulesets](https://docs.pmd-code.org/latest/pmd_userdocs_making_rulesets.html)
- [PMD warning suppressions](https://docs.pmd-code.org/latest/pmd_userdocs_suppressing_warnings.html)
- [PMD incremental analysis](https://docs.pmd-code.org/latest/pmd_userdocs_incremental_analysis.html)
- [PMD CPD](https://docs.pmd-code.org/latest/pmd_userdocs_cpd.html)
