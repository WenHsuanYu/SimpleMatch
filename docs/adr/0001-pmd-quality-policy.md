# PMD quality-policy rollout

Status: accepted. Amended 2026-08-03 and 2026-11-16 (rule addition and developer-approval governance).

Adopt PMD 7.24.0. The Gradle 9.6.1 wrapper officially supports no higher PMD version.
Analyze production Java only. Use the explicit ruleset in `config/pmd/simplematch-design.xml`.
Disable the default PMD rulesets. Each module's `pmdMain` task and the repository's `staticAnalysis`
lifecycle enforce every `<rule ref>` in that XML.

Do not use broad baselines. Keep compatibility exceptions narrow. Document each exception.
Track each exception. This policy separates PMD adoption from a future Gradle upgrade and keeps
refactoring debt visible across the codebase.

## Historical 2026-08-03 amendment

The initial rollout used five design rules. The 2026-08-03 amendment adopted 47 explicit rules in
the XML. The XML was the single source of truth, and the former `PmdPolicy` test checked a fixed
count. Gradle passed the XML directly to PMD. The fixed count was later retired by the
developer-approval governance amendment below; it is not a current requirement.

## Historical single-ruleset consolidation amendment

At consolidation, `config/pmd/simplematch-design.xml` retained its then-current 47 rules and
configuration, including PMD's default `ExcessiveParameterList` threshold. The original
instruction prohibited any agent modification. The repository retired the temporary
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

## Owner-authorized design rule amendment (2026-11-16)

Issue #208 documents a skill-authored specification, independent review, and a rule-specific baseline.
The owner expressly approved a change to the single authoritative PMD ruleset, conditional on
writing the specification first. The earlier 47-rule freeze remains the historical policy.

The approved addition was `category/java/design.xml/MutableStaticState` with PMD 7.24.0 defaults.
The single explicit ruleset reached 48 references at this amendment. All earlier references and
thresholds were preserved; 48 is a dated observation, not a permanent required rule count.
Keep `ExcessiveParameterList` with its default threshold of ten as the sole parameter-count
check. No second ruleset, category-wide import, or new Checkstyle parameter check is permitted.

The new rule reports non-private, non-final static field declarations. It does not detect
private mutable static fields or mutation through a final field holding a mutable object.
An isolated production-Java PMD baseline found zero violations across the selected Gradle
`pmdMain` tasks. A disposable public static non-final field caused the owning `pmdMain`
to fail with exactly one `MutableStaticState` finding. Private static and public static
final examples passed. These fixtures were never part of production source.

This is a narrow, separately approved exception to the former no-edit instruction, not
general permission to change the PMD policy. Future changes require their own rationale,
rule-specific baseline, acceptance tests, and owner approval.

**Rollback:** If the new rule causes verified false positives, compatibility regression,
or an unrecoverable quality-gate failure, preserve the original finding and source identity.
Under a separately developer-approved rollback, remove only the affected rule from the canonical
XML. Do not restore a fixed-count constant or assume that the current count is still 48. Update
the current policy document and append the rollback decision to this ADR. Re-run the relevant
structural policy test and `staticAnalysis`. Do not revert other rules, add a broad suppression,
or change a threshold solely to obtain a passing result.

## Developer approval for every PMD policy change (2026-11-16)

The single XML ruleset remains authoritative, but **its number of rules is no longer frozen**.
There is no parallel Kotlin list or fixed numeric expectation. The active rule count comes only
from the rule references in the XML. A previous approval does not approve later changes.

Before changing any rule reference, threshold, priority, property, or exclusion, obtain explicit
approval from the responsible human developer for the exact proposed change. Record the
developer, date, approved before/after rule settings, motivation, impact and false-positive
review, validation plan, and rollback conditions in the owning Issue or ADR. A passing build,
an approved historical change, or an agent's own assessment never substitutes for approval.

Do not introduce another ruleset, implicit PMD categories, or a second parameter-count gate.
`ExcessiveParameterList` remains the sole parameter-count rule under the current policy.
The `PmdPolicy` helper and fixed-count assertion were retired because PMD already consumes the
canonical XML directly. A count-independent structure test retains the non-empty, unique
Java-reference and parameter-owner checks without freezing an individual historical addition.
