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
- Before local Docker/Kubernetes cleanup, inspect containers, images, volumes, and their references. Preserve running containers, canonical kind
  clusters, images used by them, persistent named volumes, and unrelated resources. Remove only exact stopped containers, dangling images, or
  run-owned disposable volumes from completed tests. Avoid blanket prune commands and broad globs. Verify the remaining inventory and report the
  resources you removed.
- Before any test that applies Compose, Docker, kind, or Kubernetes deployment resources, read
  [docs/agents/deployment-test-lessons.md](docs/agents/deployment-test-lessons.md) and complete its preflight checklist. Treat a known lesson as a
  gate: verify its prevention check before starting the dependent test. After a new or repeated deployment-test failure, update that document before
  declaring the run complete. Include the symptom, root cause, safe fix, prevention check, and redacted evidence.
- Before an expensive certification run, review the complete-local Gradle task graph, source revision, and image inputs against the
  [execution contract](docs/verification/local-certification-phase-dag.md). Use `./gradlew verifyAll` for final source-aligned local acceptance.
  Require a new run-owned Kubernetes evidence directory and completed cleanup. Use normal Gradle and image-builder caches for deterministic builds.
  Focused selections and interrupted runs cannot establish a complete-local PASS. Retained-environment diagnostics require their documented
  current-state and ownership checks and cannot upgrade an earlier aggregate result. Documentation or issue metadata changes that do not change
  executable inputs require no runtime rerun. Changes to execution or acceptance semantics require corresponding contract and fixture review.
- Do not run the retired CDC continuation or Risk CDC observer scripts. P5b removed the aggregate that produced their retained `run-context` and
  changed its source fingerprint. The former continuation cannot produce current-source evidence. Run `./gradlew verifyCdc` for current broker,
  connector, and publication contracts. It does not verify deployed Kubernetes Risk CDC telemetry or Connect Pod/task reassignment. Do not reuse or
  upgrade historical diagnostic reports. Integrate scenario changes in one source-aligned batch before a final `verifyAll` execution. Rerun after
  failure or invalidation.
- For every implementation or test task, invoke the repository's `implement` skill when it is available and follow its acceptance-criteria, test,
  review, and handoff workflow. Keep the implementation slice narrow, run focused checks during the work, and run the relevant complete suite before
  handoff. An explicit user instruction about diagnosis-only scope, staging, or committing takes precedence and must be recorded in the handoff.
- Maintain disk space as part of deployment-test hygiene. At the end of a run, inventory Docker containers, images, volumes, builder cache, and
  generated build caches before removing anything. Remove only stopped run-owned containers, unreferenced disposable volumes, dangling images, or
  explicitly stale run-owned cache entries. When multiple tags resolve to the same image identity, check every older tag for references. Include
  running containers, canonical kind clusters, evidence, and other active workflows. Keep only the newest tag if none of the older tags has such a
  reference. Never substitute broad prune commands for this inventory. Verify the inventory after cleanup and report the reclaimed space.
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
- If the task matches a workflow skill, treat that skill as required guidance, not optional reading.
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

1. Analyze the request and scope.
2. Design the smallest safe slice.
3. Write or update tests first.
4. For PostgreSQL changes, design the migration, SQL review, and verification plan before editing.
5. Implement the change.
6. Run targeted tests.
7. Run `./gradlew staticAnalysis` for Java work.
8. Run the relevant module, service, or certification smoke test when available.
9. For PostgreSQL changes, run the relevant Flyway task or migration test and review SQL/index/query implications.
10. Update the owning GitHub Issue and relevant README/docs. Update the remaining-work inventory only when capability status or evidence changes. Do
    not add live task checkboxes to `tasks.md`.
11. Review the diff for consistency. Check unexplained literals, shared mutable static state, overgrown view or adapter logic, and missing
    explanations for non-obvious behavior.

When a change crosses a service boundary, messaging boundary, or deployment boundary, consult the workflow doc and the relevant cloud design
patterns before coding.

## Layer 3: Skill routing

Use an available workflow skill whenever its description matches the task. Read its `SKILL.md` before acting. Follow it in addition to these
repository rules. Do not claim to use an unavailable skill. Instead, follow the relevant guidance in this file and
`docs/development/development-workflow.md`.

- Use `asd-ste100` when writing or editing documentation. Let the skill select the mode from the text type. Use Strict for procedures, error
  messages, and tool descriptions. Use STE-flavored for READMEs, PR descriptions, and explanatory prose. In STE-flavored mode, apply the sentence
  rules and treat the vocabulary rules as guidance. Preserve technical terms, identifiers, and the meaning of requirements.
- Use `diagnosing-bugs` to investigate reported failures, errors, regressions, or performance problems. Diagnose first. Implement a fix only when
  requested.
- Use `tdd` when the request explicitly asks for test-first development, red-green-refactor, or integration-test-led work. Regardless, keep the
  default test-first workflow in Layer 2 for all non-trivial changes.
- Use `codebase-design` for non-trivial module boundaries, excessive responsibilities, refactoring seams, testability, or interface-deepening
  decisions.
- Use `design-an-interface` when the user asks to explore or compare API/module interface alternatives.
- Use `domain-modeling` when introducing or clarifying business terminology, bounded concepts, or an architectural decision.
- Use `prototype` only for a deliberately throwaway experiment that answers a design question. Do not treat a prototype as production
  implementation.
- Use `code-review` for reviews of changes since a commit, branch, tag, or merge-base. Apply its built-in code-smell heuristics to design risks
  that static analyzers cannot prove. Use `resolving-merge-conflicts` for an in-progress merge or rebase.
- Use `research` when the task requires external technical or product research captured as a Markdown artifact in this repository.
- Use `setup-pre-commit` only when adding or changing Husky, lint-staged, or pre-commit automation. Use `git-guardrails-claude-code` only for Claude
  Code git-safety hooks.
- Use `github:github`, `github:gh-address-comments`, `github:gh-fix-ci`, and `github:yeet` only for the corresponding GitHub repository,
  review-comment, GitHub Actions, and publish-a-draft-PR workflows.
- Use `openai-docs` only for OpenAI/Codex product or API questions, and `find-skills` when the user asks to discover or install an additional skill.

For Spring Boot, JUnit, Javadocs, PostgreSQL, distributed-systems, and native changes, no repository-specific skill is currently required. Follow
Layers 1 and 2 directly. Preserve the Java/Spring/Javadoc/Flyway rules and explicitly assess cross-service reliability concerns. Run the relevant
Gradle, Flyway, or CMake validation. If multiple available skills match, use all of them in the smallest order that covers the task.

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

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:

- For codebase questions, first run `graphify query "<question>"` when `graphify-out/graph.json` exists. Use `graphify path "<A>" "<B>"` for
  relationships. Use `graphify explain "<concept>"` for focused concepts. These commands return a scoped subgraph, usually much smaller than
  `GRAPH_REPORT.md` or raw grep output.
- Hooks or incremental updates can leave `graphify-out/` files dirty. Do not skip graphify only because the graph files are dirty. Skip graphify
  only if the task concerns stale or incorrect graph output, or if the user explicitly says not to use it.
- If `graphify-out/wiki/index.md` exists, use it for broad navigation instead of raw source browsing.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when query/path/explain do not provide enough context.
- After changing code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Kubernetes / kind image runtime integrity

- Do not treat `docker image inspect`, `ctr images ls`, or `crictl inspecti` as sufficient proof that an image is usable inside a kind node. Image
  metadata may exist even if containerd content or snapshot state is corrupt.

- Before deployment or certification that depends on node-local helper images, verify that the pinned image can actually start on every eligible kind
  worker. Prefer an execution probe, such as running `/bin/sh -c 'true'` through that node's containerd.

- If a Pod fails with `exec format error`, a missing layer digest, a snapshot preparation failure, or an image that inspects correctly but cannot
  start, follow these steps:
  1. Check host/node architecture and the pinned image platform.
  2. Verify that the same image executes successfully outside the affected node.
  3. Remove only the affected node's stale image/snapshot state. Re-pull the exact pinned image.
  4. Re-run the execution probe.
  5. If containerd state remains inconsistent, rebuild the canonical kind cluster instead of repeatedly retrying the workload.

- Do not classify PVC `Pending` as a StorageClass or Kubernetes configuration defect until you check the local-path provisioner/helper Pod and
  target-node container runtime.

- Do not delete the cluster as the first remediation step. Preserve failure evidence and prefer the smallest scoped fix first.
