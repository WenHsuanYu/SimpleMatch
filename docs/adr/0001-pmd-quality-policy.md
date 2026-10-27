# PMD quality-policy rollout

Status: accepted. Amended 2026-08-03.

Adopt PMD 7.24.0. The Gradle 9.6.1 wrapper officially supports no higher PMD version.
Analyze production Java only. Use the explicit ruleset in `config/pmd/simplematch-design.xml`.
Disable the default PMD rulesets. Each module's `pmdMain` task and the repository's `staticAnalysis`
lifecycle enforce every `<rule ref>` in that XML.

Do not use broad baselines. Keep compatibility exceptions narrow. Document each exception.
Track each exception. This policy separates PMD adoption from a future Gradle upgrade and keeps
refactoring debt visible across the codebase.

## Amendment

The initial rollout used five design rules. The policy now adopts all 47 rules in the XML file.
Do not maintain a second, abbreviated list in Kotlin or Markdown. The XML file is the single source
of truth. `PmdPolicy` tests check its rule references and expected count. Gradle passes that same
file directly to PMD.

## Single-ruleset consolidation amendment

`config/pmd/simplematch-design.xml` retains its existing 47 rules and their existing configuration,
including PMD's default `ExcessiveParameterList` threshold. Agents must not create, modify, rename,
or delete PMD ruleset files. The repository retired the temporary
`config/pmd/completed-parameter-safety.xml` file and the later Checkstyle task, `parameterSafetyMain`.
PMD is now the only automated parameter-count gate. Checkstyle has no `ParameterNumber`
configuration or dedicated parameter-safety task.

Checkstyle, PMD, SpotBugs, and Error Prone block builds with findings. Error Prone completed its
two-stage ratchet. First, the implementation removed the existing warnings. Then it removed
`allErrorsAsWarnings`. `ErrorPronePolicyTest` proved that a finding fails the ordinary build
lifecycle. Generated source handling stayed unchanged.

On 2026-08-03, the closure slice added private constructors to five Spring Boot entry points.
This completed Issue #22's Checkstyle suppression review. The suppression file now retains only
the generated-source boundary. The slice also removed the `FutureReturnValueIgnored` source
suppression. It used `@CanIgnoreReturnValue` to declare that callers could ignore the compatibility
publisher's fire-and-forget return value. The repository later deleted that publisher, so the
exception no longer appears in production source.

The implementation satisfies the technical criteria for Issues #21 and #22. GitHub status updates
follow the implementation commit. This specification includes Error Prone cleanup and blocking enforcement.
It does not retroactively expand either issue.
