# Spec: Evidence-Gated PMD Design Policy Expansion

Status: Skill-reviewed and locally committed (`051c15e2`); Hosted CI pending.
Date: 2026-11-16 (Asia/Taipei).
Method: `to-spec` for requirements and testing seams, `codebase-design` for module boundaries,
`code-review` for Standards/Spec review, and `research-with-sources` for PMD rule semantics.
Tracker: https://github.com/WenHsuanYu/SimpleMatch/issues/208

## Problem Statement

The repository documents code-smell avoidance and Java design constraints. Some requirements are
already checked by PMD, Checkstyle, SpotBugs, and Error Prone. Others are design heuristics that
cannot be checked reliably from syntax alone.

Before the 2026-11-16 amendments, the Java policy used one explicitly enumerated,
blocking PMD 7.24.0 ruleset with 47 rules. Its policy documents and tests froze
that set. The owner approved an amendment conditional on writing a skill-based
specification first. Later governance refinements are recorded below.

Adding every available code-smell rule would create overlapping quality gates, false positives,
and potentially unnecessary changes to domain and protocol code. The change needs an explicit
rule-selection process, observable enforcement, and a bounded verification cost.

## Solution

Expand the existing single PMD policy only with individually justified, evidence-backed rules.
Use the current production Java analysis seam and the existing `staticAnalysis` lifecycle.
Keep design heuristics in Skills or human review when no reliable static predicate exists.

The initial candidate is **`MutableStaticState`**, because exposed non-final static fields
permit unguarded reassignment. Its predicate is limited to **non-private, non-final static
fields**. It does not detect private mutable static fields or mutation through a final
reference to a mutable object. PMD says `AssignmentToNonFinalStatic` is not needed when this
rule is active. Treat that rule as a rejected duplicate unless independent evidence disproves
the overlap. Do not activate a candidate until a complete in-scope baseline shows that any
findings can be resolved without changing behavior.

`AvoidDuplicateLiterals` and `GodClass` are exploratory candidates, not part of the first
blocking slice. Do not add `LawOfDemeter` as a default blocking rule: legitimate DTO, protobuf,
builder, and adapter access chains make its signal ambiguous.

## User Stories

1. As a maintainer, I want one authoritative PMD rule list so that an agent cannot apply two
   conflicting design policies.
2. As a code contributor, I want a rule to identify unsafe exposed static state so that shared
   mutable ownership is visible before merge.
3. As a reviewer, I want each new rule tied to one concrete risk so that a name alone is not
   mistaken for proof of better architecture.
4. As a maintainer, I want a baseline inventory before enforcement so that existing code does
   not become non-buildable without an explained migration.
5. As a contributor, I want protocol constants and legitimate DTO access to remain valid so
   that design lint does not require artificial wrappers or magic-number suppressions.
6. As a module owner, I want all handwritten production Java modules checked through the same
   Gradle task so that code cannot bypass a new rule in one service.
7. As a test maintainer, I want positive and negative policy fixtures so that the rule is not
   considered active merely because its name appears in XML.
8. As a CI maintainer, I want the existing failure semantics and report upload retained so
   that a new rule cannot silently become advisory.
9. As a repository owner, I want parameter-count checks to remain owned by the existing
   `ExcessiveParameterList` rule so that no competing threshold is introduced.
10. As a reviewer, I want any new exception restricted to a named symbol and rationale so
    that broad suppression cannot hide quality regressions.
11. As an architecture maintainer, I want the previous 47-rule decision preserved in an ADR
    amendment so that the historical decision is not rewritten as if it never existed.
12. As a contributor, I want human-review-only smells clearly identified so that lint output
    does not claim to prove single responsibility, encapsulation, or domain ownership.
13. As a release reviewer, I want exact commands, source revision, and outcome recorded so
    that a passing quality check has a reproducible scope.

## Implementation Decisions

1. **Source of truth.** Use the existing explicitly enumerated PMD XML. Do not create a
   second ruleset, category-wide reference, duplicate parameter-count check, or new lint
   lifecycle that bypasses the existing Java quality plugin.
2. **First slice.** Evaluate PMD 7.24 `category/java/design.xml/MutableStaticState`
   with its default properties. Record every violation, its cause, false-positive status,
   and the effect of a proposed fix on public or persistence contracts.
3. **Preserve behavior.** Existing rules, parameters, generated-source exclusions, Java
   dependency versions, and the production/test boundary must remain unchanged.
4. **Rule acceptance.** A rule can become blocking only if its meaning matches the targeted
   risk, the existing production baseline is clean after safe fixes, and a test proves that
   a representative violation fails while a valid example passes.
   If safe activation requires unrelated production edits, reject or defer that candidate.
5. **Failure semantics.** Keep the current blocking Gradle and CI paths. Do not set
   `ignoreFailures`, raise `maxFailures`, or add a blanket exclusion to obtain a green result.
6. **Policy provenance.** Record the owner's 2026-11-16 authorization in a new ADR
   amendment. Retain historical policy decisions and their old rule count as history.
   Make the current count and permitted amendment procedure explicit in current guidance.
7. **Count invariant.** Update the policy-count constant and its existing test only when
   the ruleset actually changes. Keep the uniqueness, Java-only, and parameter-rule checks.
   Preserve the exact original 47 references and their properties. A single accepted new
   reference changes the count from 47 to 48, not to a derived or arbitrary number.
8. **Suppressions.** First try a correct source-level remedy. Any proposed exception
   requires the owner's explicit approval, a narrow scope, a local reason, and a retirement
   condition. A broad baseline or category-wide suppression is out of scope.
9. **Design review.** Keep duplicate logic, feature envy, data clumps, message chains,
   transaction ownership, and other context-dependent smells with the applicable Skills.
   A syntactic proxy is not equivalent to a design rule.
10. **Compatibility.** Do not change production behavior, public interfaces, database
    schemas, Kafka/FIX contracts, Docker resources, or Kubernetes acceptance semantics.

## Testing Decisions

The primary test seam is the existing production Java `pmdMain` task, aggregated by
`staticAnalysis`. The policy parser and its existing ruleset test provide a second, narrow
structural seam. Do not create a parallel gate solely to exercise the new rule.

The implementation must satisfy these acceptance criteria:

- **AC-01 — Spec first:** this specification and an independent Standards/Spec review exist
  before any change to the authoritative PMD ruleset.
- **AC-02 — Baseline:** record the tested PMD version, selected Java modules, candidate rule,
  all in-scope findings, and false-positive disposition before blocking activation. Run a
  temporary copy of the canonical ruleset with the candidate enabled through the existing
  Gradle `pmdMain` path, in an isolated disposable workspace. Do not commit a second
  ruleset or accept a lexical search as a PMD baseline.
- **AC-03 — Exactly one owner:** Gradle reads exactly one explicit PMD ruleset. No second
  checked-in or in-memory ruleset and no implicit default categories are added.
- **AC-04 — Invariant preservation:** the existing 47 rule references remain, the active
  rule list is unique, and only separately selected candidate references are added. Keep
  all prior rule properties and thresholds unchanged. If one candidate is accepted,
  update the policy-count constant and its test to 48 in the same change.
- **AC-05 — Parameter-count owner:** the existing `ExcessiveParameterList` threshold
  remains at its default threshold of ten and is the only parameter-count enforcement.
  Do not add Checkstyle `ParameterNumber`, `parameterSafetyMain`, or a second PMD owner.
- **AC-06 — Red/green:** a minimal Java fixture with a public or package-visible non-final
  static field fails the candidate rule. Private non-final static fields and public final
  static fields pass the candidate rule, documenting its actual limits. Preserve fixture
  evidence; retain a permanent test only if it protects a continuing rule contract.
- **AC-07 — Scope:** handwritten production Java is analyzed; generated and test sources
  retain the existing exclusions.
- **AC-08 — Blocking:** the owning `pmdMain` and root `staticAnalysis` fail for a
  representative violation, with the new rule identified in the PMD report. No warning
  demotion or blanket suppression is introduced.
- **AC-09 — Compatibility:** targeted unit tests and applicable Java static analysis pass
  on the final edited source. Runtime or deployment certification is not claimed.
- **AC-10 — Governance:** the accepted ADR and current policy documentation reflect
  the new approved amendment, active count, rationale, exceptions, and rollback.
- **AC-11 — Scope audit:** only the intended policy, invariant tests, and related docs
  change. Any required production refactor is a separately scoped, behavior-tested task
  and blocks this narrow policy slice. Preserve existing unrelated working-tree edits.
- **AC-12 — Evidence:** record PASS/FAIL/BLOCKED separately, exact commands, rule count,
  source state, and remaining risks. A failed or unrun gate cannot be called PASS.

## Candidate Disposition

| Candidate | Status in this specification | Reason |
| --- | --- | --- |
| `MutableStaticState` | First blocking candidate, pending baseline | Detects non-private, non-final static fields only; not a concurrency proof |
| `AssignmentToNonFinalStatic` | Reject as duplicate by default | PMD says it is not needed with `MutableStaticState`; require contrary evidence |
| `AvoidDuplicateLiterals` | Report-only candidate | Repeated protocol, JSON, SQL, and annotation strings may be legitimate |
| `GodClass` / `TooManyFields` | Defer | Existing size and complexity rules already cover part of the signal |
| `LawOfDemeter` | Do not activate by default | Fluent APIs and generated DTO access can produce design false positives |
| `ExcessiveParameterList` | Keep current rule and threshold | Exclusive parameter-count owner |
| CPD / new custom AST rules | Out of scope | Requires separate engine or substantial maintenance and gate design |

## Out of Scope

- Adding a Markdown linter or changing documentation formatting policy.
- Adding a second PMD ruleset or a second parameter-count checker.
- Automatically fixing all historical code smells or requiring an unrelated refactor.
- Activating every available PMD design rule.
- Adding CPD, Checkstyle design rules, or clang-tidy in the same change.
- Altering production services, published APIs, migrations, or infrastructure to satisfy
  a heuristic without proving the design improvement.

## Further Notes

- Pre-amendment authority: `config/pmd/simplematch-design.xml` (47 rules at baseline; 48 after the authorized addition),
  `build-logic/src/main/kotlin/com/simplematch/gradle/PmdPolicy.kt`,
  `build-logic/src/test/kotlin/com/simplematch/gradle/PmdPolicyTest.kt`, and
  `build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchJavaQualityPlugin.kt`.
- Governance owners: `docs/adr/0001-pmd-quality-policy.md`,
  `docs/reference/pmd-quality-policy.md`, and `AGENTS.md`.
- Existing production code and generated-source boundaries must be read from the actual
  configured Gradle tasks, not inferred from file counts.
- Read-only source scanning found no obvious single-line non-private, non-final static
  declarations in 636 main Java files. This is a heuristic, not a PMD baseline or PASS.
- Read-only literal scanning found repeated JSON/protocol field names. This supports a
  report-only evaluation of `AvoidDuplicateLiterals`, not immediate blocking enforcement.
- Official rule catalog: https://docs.pmd-code.org/pmd-doc-7.24.0/pmd_rules_java.html
- Official ruleset/Gradle guidance: https://docs.pmd-code.org/pmd-doc-7.24.0/pmd_userdocs_tools_gradle.html
- The original 47-rule freeze remains valid historical context. The owner's explicit
  authorization permits a **new, reviewed amendment**, not unrestricted future changes.
- If no candidate meets the baseline and compatibility gates, publish a no-go decision
  with evidence and leave the canonical ruleset unchanged. This is an acceptable outcome.

## Local implementation and acceptance evidence

Historical source baseline: `96d4b005594e67fe1698ff80b458edf0ef8c375d`.
These observations came from an uncommitted worktree with other developer-directed
changes. The approved rule and governance edits were later committed locally as
`051c15e2` (2026-11-17). The original results do not prove complete-local or
Hosted CI acceptance for that commit.

An isolated, disposable worktree at the baseline revision was used for trial analysis.
Its only initial source edit was the candidate rule in a copy of the canonical XML.
The worktree and its temporary Java fixture were removed after the trial.

| Gate | Observed result |
| --- | --- |
| Prior 47 references and properties | PASS: unchanged; one new `MutableStaticState` reference |
| Candidate baseline, all `pmdMain` tasks | PASS: 15 reports, zero violations; 53 tasks, 40 seconds |
| Prohibited field fixture, owning `pmdMain` | EXPECTED FAIL: one `MutableStaticState` finding |
| Allowed private static and public static final fixture | PASS: owning `pmdMain`, 9 seconds |
| Prohibited fixture, root `staticAnalysis` | EXPECTED FAIL: named PMD finding; SpotBugs also flagged the disposable fixture |
| Canonical root `staticAnalysis` | PASS: 120 tasks, 1 minute 41 seconds |
| `PmdPolicyTest` | PASS: 7 tasks, 10 seconds |
| `git diff --check` and XML rule-set invariant | PASS: 48 unique references, original 47 unchanged |
| Hosted Java CI / complete-local `verifyAll` | NOT RUN: no published source revision; Kubernetes is outside this policy slice |

Evidence: `out/verification/pmd-policy-208/summary.json` and
`out/verification/pmd-policy-208/positive-fixture-pmd.xml` (ignored, local files).
The PMD report identifies rule `MutableStaticState` on the deliberate trial declaration.
The red aggregate also produced a SpotBugs unused-field finding from the disposable fixture, which does not
replace the PMD-specific evidence.

The canonical post-amendment `staticAnalysis` run produced 15 fresh zero-violation PMD
XML reports. The reports were copied to the local evidence directory, with module names
and hashes in `out/verification/pmd-policy-208/canonical-static-analysis-manifest.json`.
The manifest records 636 curated handwritten Production Java source paths, their combined
SHA-256 fingerprint, and SHA-256 values for the canonical PMD input and policy tests.
An older report for a retired module was excluded because it is not present in the
current Gradle settings. These archived reports are **not** the original isolated
candidate-baseline report files, which were discarded with that disposable worktree.
The negative-fixture PASS is supported by the observed Gradle result, but its original
report was not archived. These results describe the earlier uncommitted worktree,
not a post-commit Hosted CI run.

AC-01 through AC-08, AC-10, AC-11, and AC-12 are satisfied for the local implementation.
AC-09 is satisfied for the policy test and the applicable Java static-analysis suite.
Service behavior, integration tests, Kubernetes, full `verifyAll`, and hosted CI have not
been re-run. The change adds no production Java behavior, API, schema, or deployment input.
The previous Java CI ran `staticAnalysis` without explicitly invoking the
included build's `PmdPolicyTest`. This coverage gap was corrected by an
explicit included-build test step during the later 2026-11-16 review.
The original PMD runtime gate was never disabled by this gap.

## Subsequent developer-approved governance refinement (2026-11-16)

After the initial 47-to-48-rule acceptance, the developer explicitly changed the
governance requirement: **the total PMD rule count must no longer be fixed**. Every
subsequent rule addition, removal, replacement, property, priority, threshold, or
exclusion change instead requires prior approval from the responsible human developer.
The approval record must identify the exact change and its validation rationale.
Approval for one change cannot be reused for another. See the current ADR and PMD
reference policy for the controlling procedure.

The original 47-to-48 acceptance criteria and source evidence above are historical
facts about the `MutableStaticState` addition. They do not establish a permanent
48-rule invariant. This governance refinement does not add or remove any PMD rule.

The `PmdPolicy` helper was evaluated using the `codebase-design` deletion test.
Only its ruleset-path constant was referenced by production build configuration,
and that reference had a single caller. Its regex parser and hard-coded expected
count were only used by its dedicated test. The helper added indirection but did
not execute PMD rules; it was removed. The unchanged canonical XML path is now
supplied directly to the existing Java quality plugin.

The existing `PmdPolicyTest` name and test selection remain available. The test
now parses the canonical XML directly and checks nonempty, unique, explicit Java
rule references plus the current sole parameter-count rule. It does not check a
numeric total or pin the previously added `MutableStaticState` reference.
These structural checks have continuing regression value independent of a rule
count. The Java CI workflow now explicitly invokes the included build's
`PmdPolicyTest`; no Hosted run for the local implementation commit is claimed.

Local validation of this governance/refactoring change:

- `./gradlew --offline --no-daemon :build-logic:test --tests 'com.simplematch.gradle.PmdPolicyTest' --console=plain --max-workers=2`:
  PASS, 7 actionable tasks (10 seconds after the last test adjustment).
- `./gradlew --offline --no-daemon staticAnalysis --console=plain --max-workers=2`:
  PASS, 120 actionable tasks (1 minute 22 seconds).
- The actual PMD XML remains unchanged by this follow-up. The plugin still sets
  `ruleSets = emptyList()` and uses exactly one `ruleSetFiles` path.
- These are local pre-commit worktree results, not a clean-revision Hosted CI
  or full `verifyAll` acceptance.

## Subsequent structural-test review (2026-11-16)

The Standards review found a test false negative: a foreign-namespace `<rule>`
could evade the original namespace-filtered lookup. A disposable JUnit
`@TempDir` fixture proved this failure (expected RED: 1 of 2 tests failed).
The parser test now verifies the canonical `ruleset` root namespace and
compares all local-name `rule` descendants with the canonical namespace list.
Both targeted tests then passed (GREEN: 7 Gradle tasks, 2 executed).

The Java workflow now explicitly runs the included-build structural test
after `staticAnalysis`; this CI configuration has not been run on GitHub.
This update does not change `config/pmd/simplematch-design.xml`, its
approved rule list, or the rule-change authorization policy.
