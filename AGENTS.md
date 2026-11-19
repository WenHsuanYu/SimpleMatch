# SimpleMatch Repo Instructions

These instructions apply to the whole repository.

## Layer 1: Non-negotiable guardrails

- Read [docs/development/development-workflow.md](docs/development/development-workflow.md) before making any change.
- Identify the affected files, services, and modules before editing.
- Write acceptance criteria first, then choose the smallest safe slice.
- Keep code, tests, documentation, the owning GitHub Issue, and configuration aligned in the same change. Use `tasks.md` only as historical
  navigation.
- Do not widen scope without a concrete reason.
- For every code change, identify applicable design smells before editing and review them again in the final diff. Use the `code-review` skill's
  built-in heuristics when available. Report unresolved design risks.
- Separate temporary verification tools from permanent regression tests. Use one-off commands or scripts to check changes, such as workflow syntax,
  remaining callers, and existing tests. Remove temporary scripts after verification. Do not commit them or retain them as test infrastructure.
- Add or retain permanent regression tests only for current requirements. Each test must provide independent regression value that justifies its
  ongoing maintenance cost. Do not retain tests solely to preserve historical coverage.
- Before retiring a test, confirm the deletion scope, remaining verification owners, and intentional loss of verification. Remove callers and fixtures
  used only by the retired test. Do not create another test only to prove that retirement is safe. Do not require a one-for-one replacement.
  Preserve basic regression protection for behavior that remains required.
- Before local Docker, kind, or registry image cleanup, inventory Host Docker images, node containerd content, registry
  manifests/tags/digests, and their references. Check containers, Pods/Jobs, deployed image identities, image locks, rollback
  versions, and retained verification artifacts. Classify each image candidate as PRESERVE, RETIRE, or UNKNOWN. Preserve
  required and UNKNOWN identities. A successful new build does not retire an older image. Preserve running containers, the
  canonical kind cluster, referenced images, persistent named volumes, and unrelated resources. Remove only individually
  verified RETIRE images, exact stopped run-owned containers, or run-owned disposable volumes within the authorized scope.
  Never use broad Docker prune commands, broad selectors, or direct containerd snapshot deletion. Follow the documented
  ownership and namespace/PV checks before any node-local CRI pruning. Registry manifest deletion and actual garbage
  collection require separate explicit approval, an exact reviewed retirement list, and controlled registry writes.
  Never purge the registry data volume to remove one image. Verify the remaining inventory and record removed identities.
- Before any test that applies Compose, Docker, kind, or Kubernetes deployment resources, read
  [docs/agents/deployment-test-lessons.md](docs/agents/deployment-test-lessons.md) and perform only the preflight checks required by the
  selected runtime and operation. A known lesson is a gate only when its failure mode applies to the selected test. Verify the
  applicable prevention check before that test. After a new or repeated deployment-test failure, record
  the symptom, known root cause, safe fix (if established), prevention check, and redacted evidence in the owning Issue or run
  evidence before declaring the run complete. Update the lessons document only if the failure meets its retention criteria.
- Before an expensive certification run, review the complete-local Gradle task graph, source revision, and image inputs against the
  [current certification runbook](docs/verification/local-production-like-certification.md). Use `./gradlew verifyAll` for final source-aligned local acceptance.
  Require a new run-owned Kubernetes evidence directory and completed cleanup. Use normal Gradle and image-builder caches for deterministic builds.
  Focused selections and interrupted runs cannot establish a complete-local PASS. Retained-environment diagnostics require their documented
  current-state and ownership checks and cannot upgrade an earlier aggregate result. Documentation or issue metadata changes that do not change
  executable inputs require no runtime rerun. Changes to execution or acceptance semantics require corresponding contract and fixture review.
- Do not run the retired CDC continuation or Risk CDC observer scripts. P5b removed the aggregate that produced their retained `run-context` and
  changed its source fingerprint. The former continuation cannot produce current-source evidence. Run `./gradlew verifyCdc` for current broker,
  connector, and publication contracts. It does not verify deployed Kubernetes Risk CDC telemetry or Connect Pod/task reassignment. Do not reuse or
  upgrade historical diagnostic reports. Integrate scenario changes in one source-aligned batch before a final `verifyAll` execution. Rerun after
  failure or invalidation.
- For substantial specification- or ticket-driven implementation, use the `implement` skill only when it is available and its
  invocation policy permits use, or when the user explicitly selects it. For focused fixes and test tasks, follow Layers 1 and 2
  and use only the relevant narrower skills. Keep the implementation slice narrow, run focused checks during the work, and run
  the relevant complete suite before handoff. Record explicit user limits on diagnosis, validation, staging, or committing in
  the handoff. A skill's workflow does not itself authorize commits, pushes, publication, deployment, or scope expansion.
- Maintain disk space as part of deployment-test hygiene. Inventory Docker builder caches, generated build caches, and
  duplicate image tags after a run. Remove only individually verified stale run-owned caches or unused image aliases within
  the authorized scope. Preserve caches needed by active or planned workflows. Do not infer that older tags or shared layers
  are disposable from their age or a successful rebuild. Compare Host Docker, kind containerd, and registry storage before
  and after cleanup. Report measured reclaimed space. Follow the
  [local registry and resource lifecycle](docs/operations/local-registry-resource-lifecycle.md) for store-specific procedures.
- PostgreSQL schema changes must use versioned Flyway migrations via the shared `simplematch.flyway-service` convention. Do not reintroduce runtime
  migration or ad hoc schema initialization flows.
- For Java changes, require tests and Javadocs where members are public, protected, or non-obvious.
- For Java, prefer records for simple immutable carriers. In `services/*`, use Lombok only for narrow Spring boilerplate, such as required-args
  constructors or logging. Avoid broad annotations such as `@Data`. Keep domain, config, mutable, validation-heavy, normalization-heavy,
  custom-equality, or defensive-copy types handwritten.
- For Native C++ changes, follow [Native static analysis](docs/reference/native-static-analysis.md). Use Google formatting and the configured
  clang-tidy checks. Report-only clang-tidy findings are not a passing Hosted CI gate; review ownership, protocol, and hot-path implications.
- For Spring JDBC, prefer `@Transactional` on public concrete application or service methods that own a business transaction. Keep `JdbcTemplate` in
  thin repository adapters. If only part of a method should be transactional, use `TransactionTemplate` or another narrow programmatic boundary.
  Use that boundary instead of making the full method transactional or moving transaction ownership into repositories.
- Apply a workflow skill's guidance when it is available, permitted to invoke, and materially relevant to the task. Do not
  bypass its invocation restrictions or let its instructions override the user's scope and authorization.
- For dependency lock issues, use `--update-locks` to update the relevant locked dependencies. Do not edit dependencies sourced from version
  catalogs to resolve a lock issue.
- Use `config/pmd/simplematch-design.xml` as the sole authoritative PMD ruleset. Its rule count is not frozen. Before adding, removing, or changing
  any rule, threshold, priority, property, or exclusion, get explicit approval from the responsible human developer for that exact change. Record
  the approver, date, approved changes, rationale, and verification plan in the owning Issue or ADR. Earlier approvals never authorize later changes.
  Do not create, rename, or delete the canonical ruleset or add another one. Keep `ExcessiveParameterList` as the sole parameter-count check. Do
  not add Checkstyle `ParameterNumber`, `parameterSafetyMain`, or another parameter-count gate without a separately approved policy decision.
- Treat every static-analysis warning as a design signal. First resolve it through ownership, encapsulation, validation, or a narrower interface.
  Prove the result with tests. Use `@SuppressWarnings` only if no viable source-level remedy exists and the user explicitly approves it. Scope the
  annotation narrowly and give a local rationale. Never use it only to satisfy a quality gate.

## Layer 2: Default development workflow

Follow [docs/development/development-workflow.md](docs/development/development-workflow.md) as the authoritative detailed
sequence for design, implementation, validation, documentation, and review. Apply only the steps relevant to the change.
For non-trivial behavior changes, define the failing behavior and write or update the relevant test before implementation.
Do not require a new regression test for documentation-only edits, formatting, or changes without behavior impact. Validate
those changes with appropriate focused checks. Retain Java static analysis, applicable PostgreSQL/Flyway checks, and relevant
service or certification tests as required by the workflow. Keep the owning Issue and documentation aligned; update the
remaining-work inventory only when capability status or evidence changes. Do not add live task checkboxes to `tasks.md`.
Review the final diff for consistency, unexplained literals, shared mutable state, overgrown adapters, and design risks.

When a change crosses a service boundary, messaging boundary, or deployment boundary, consult the workflow doc and the relevant cloud design
patterns before coding.

## Layer 3: Skill routing

Select workflow skills for the capabilities needed by the task, not merely because descriptions overlap. Before invoking a skill,
read its `SKILL.md` and respect its invocation restrictions, including whether the model may invoke it without explicit user
selection. Follow applicable guidance without overriding repository guardrails or user authorization. Do not claim to use an
unavailable or uninvoked skill; use this file and `docs/development/development-workflow.md` when a skill cannot be used.

- Use `asd-ste100` for English instructions, prompts, tool descriptions, or error messages when ambiguity creates a
  material risk of misinterpretation. Apply Strict to English procedures or machine-consumed instructions. Use
  STE-flavored guidance for ambiguous English explanatory prose when useful. Do not apply English grammar or vocabulary
  constraints to Traditional Chinese documentation. Preserve technical terms, identifiers, and requirement semantics.
- Use `diagnosing-bugs` to investigate reported failures, CI failures, regressions, or performance problems. Diagnose first. Implement a fix only when
  requested.
- Use `tdd` when the request explicitly asks for test-first development, red-green-refactor, or integration-test-led work. Regardless, keep the
  default test-first workflow in Layer 2 for all non-trivial changes.
- Use `codebase-design` for non-trivial module boundaries, excessive responsibilities, refactoring seams, testability,
  interface-deepening decisions, or comparisons of API/module interface alternatives.
- Use `domain-modeling` when introducing or clarifying business terminology, bounded concepts, or an architectural decision.
- Use `prototype` only for a deliberately throwaway experiment that answers a design question. Do not treat a prototype as production
  implementation.
- Use `code-review` for reviews of changes since a commit, branch, tag, or merge-base. Apply its built-in code-smell heuristics
  to design risks that static analyzers cannot prove.
- Use `research` when the task requires external technical or product research captured as a Markdown artifact in this repository.
- Use `setup-pre-commit` only when adding or changing Husky, lint-staged, or pre-commit automation. Use `git-guardrails-claude-code` only for Claude
  Code git-safety hooks.

For Spring Boot, JUnit, Javadocs, PostgreSQL, distributed-systems, and native changes, no repository-specific skill is currently required. Follow
Layers 1 and 2 directly. Preserve the Java/Spring/Javadoc/Flyway rules and explicitly assess cross-service reliability concerns. Run the relevant
Gradle, Flyway, or CMake validation. If multiple available skills overlap, use the smallest permitted set that covers the
task's necessary capabilities rather than invoking every matching skill.

## Agent skills

### Issue tracker

GitHub Issues are the executable task source of truth for open/blocked/assigned/completed state. `tasks.md` is historical navigation, the
remaining-work inventory maps capability status/evidence, and the refactor plan owns phase/transaction/rollback gates. See
`docs/agents/issue-tracker.md`.

### Commit message conventions

- Separate the subject from the body with a blank line.
- Limit the subject line to 50 characters.
- Capitalize the subject line.
- Do not end the subject line with a period.
- Use the imperative mood in the subject line.
- Wrap the body at 72 characters.
- Use the body to explain what and why rather than how.

### Triage labels

This repository uses the default five triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

This repository uses a single-context domain-documentation layout. See `docs/agents/domain.md`.

## graphify

This project has a knowledge graph at `graphify-out/` with god nodes, community structure, and cross-file relationships.

When the user explicitly types `/graphify`, follow the installed graphify skill and its invocation requirements.

Rules:

- Prefer graphify for broad architecture, cross-module dependencies, data-flow relationships, and impact analysis when its
  graph and command are available. For localized bugs, focused reviews, and direct source questions, inspect current source,
  tests, and configuration without requiring an initial graph query.
- Check that `graphify-out/graph.json` and a usable graphify command are available before querying. A graph file alone
  does not prove the command can run. If the command is unavailable, follow the skill's supported fallback when applicable
  or use direct source inspection. Report the limitation; never claim a graph query or update ran when it did not.
- Use `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` only when they
  answer a relevant question. Treat graph results as navigation aids, not substitutes for current source and tests.
- If `graphify-out/wiki/index.md` exists, use it for broad navigation. Read `graphify-out/GRAPH_REPORT.md` for broad
  architecture review or when a focused query is insufficient.
- After a change that materially affects code structure or cross-file relationships, update the graph when the command is
  usable and the task scope allows it. Documentation-only and formatting-only edits do not require `graphify update .`.
  If an update cannot run, disclose the stale-graph risk instead of treating the graph as current evidence.

## Kubernetes / kind image runtime integrity

- Do not treat `docker image inspect`, `ctr images ls`, or `crictl inspecti` as sufficient proof that an image is usable inside a kind node. Image
  metadata may exist even if containerd content or snapshot state is corrupt.

- Before deployment or certification that depends on node-local helper images, verify that the pinned image can actually start on every eligible kind
  worker. Prefer an execution probe, such as running `/bin/sh -c 'true'` through that node's containerd.

- If a Pod fails with `exec format error`, a missing layer digest, a snapshot preparation failure, or an image that inspects correctly but cannot
  start, follow these steps:
  1. Check host/node architecture and the pinned image platform.
  2. Verify that the same image executes successfully outside the affected node.
  3. Remove only the affected node's stale image content through supported runtime commands. Do not manually delete
     containerd snapshot files. Re-pull the exact pinned image.
  4. Re-run the execution probe.
  5. If containerd state remains inconsistent, preserve diagnostics and propose rebuilding the canonical kind cluster.
     Obtain explicit authorization from the workstation owner before deleting or rebuilding it. A failed probe alone does
     not authorize a cluster rebuild.

- Do not classify PVC `Pending` as a StorageClass or Kubernetes configuration defect until you check the local-path provisioner/helper Pod and
  target-node container runtime.

- Do not delete the cluster as the first remediation step. Preserve failure evidence and prefer the smallest scoped fix first.
