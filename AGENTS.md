# SimpleMatch Repo Instructions

These instructions apply to the whole repository.

## Layer 1: Non-negotiable guardrails

- Read [docs/development-workflow.md](docs/development-workflow.md) before making any change.
- Read [badCodeSmell.md](badCodeSmell.md) before writing or editing code, and treat it as an
  avoid-list for code smells during design, implementation, and refactoring.
- Identify the affected files, services, and modules before editing.
- Write acceptance criteria first, then choose the smallest safe slice.
- Keep code, tests, documentation, tasks, and configuration aligned in the same change.
- Do not widen scope without a concrete reason.
- PostgreSQL schema changes must use versioned Flyway migrations via the shared
  `simplematch.flyway-service` convention; do not reintroduce runtime migration or ad hoc schema
  initialization flows.
- For Java changes, require tests and Javadocs where members are public, protected, or non-obvious.
- For Java, prefer records for simple immutable carriers. In `services/*`, Lombok may be used
  narrowly to remove Spring boilerplate such as required-args constructors or logging, but avoid
  broad annotations such as `@Data` and keep domain, config, mutable, validation-heavy,
  normalization-heavy, custom-equality, or defensive-copy types handwritten.
- For Spring JDBC code, prefer `@Transactional` on public concrete application or service methods
  that own a business transaction. Keep `JdbcTemplate` in thin repository adapters, and when only
  part of a method should be transactional, use `TransactionTemplate` or another narrow programmatic
  boundary instead of widening the full method or moving transaction ownership into repositories.
- If the task matches a workflow skill, treat that skill as required guidance, not optional reading.
- Remember that when encountering dependency lock issues in the future, you should use --update-locks to update the relevant dependencies rather than modifying the dependencies sourced from version catalogs.
- Treat `config/pmd/simplematch-design.xml` as the immutable PMD ruleset and single source of truth.
  Agents must not create, modify, rename, or delete PMD ruleset files. The sole pre-approved
  consolidation exception is removal of `config/pmd/completed-parameter-safety.xml` while replacing
  its seven-parameter gate with the documented Checkstyle-backed Gradle task; that work must not
  change any rule or threshold in `simplematch-design.xml`.
- After completing any code task, run a concise bad-smell avoidance checklist
  against [badCodeSmell.md](badCodeSmell.md)
  and call out any remaining risks.

## Layer 2: Default development workflow

1. Analyze the request and scope.
2. Read [badCodeSmell.md](badCodeSmell.md) and identify the smells to avoid for the current change.
3. Design the smallest safe slice.
4. Write or update tests first.
5. For PostgreSQL changes, design the migration, SQL review, and verification plan before editing.
6. Implement the change.
7. Run targeted tests.
8. Run `./gradlew staticAnalysis` for Java work.
9. Run the relevant module, service, or certification smoke test when available.
10. For PostgreSQL changes, run the relevant Flyway task or migration test and review
    SQL/index/query implications.
11. Update README, tasks.md, and related docs.
12. Review the diff for consistency.
13. Finish with a bad-smell avoidance checklist against [badCodeSmell.md](badCodeSmell.md).

When a change crosses a service boundary, messaging boundary, or deployment boundary, consult the
workflow doc and the relevant cloud design patterns before coding.

## Layer 3: Skill routing

Use an available workflow skill whenever its description matches the task. Read its
`SKILL.md` before acting and follow it in addition to these repository rules. Do not claim to use a
skill that is unavailable; apply the relevant guidance in this file and
`docs/development-workflow.md` directly instead.

- Use `diagnosing-bugs` to investigate reported failures, errors, regressions, or performance
  problems. Diagnose first; implement a fix only when requested.
- Use `tdd` when the request explicitly asks for test-first development, red-green-refactor, or
  integration-test-led work. Regardless, keep the default test-first workflow in Layer 2 for all
  non-trivial changes.
- Use `codebase-design` for non-trivial module boundaries, refactoring seams, testability, or
  interface-deepening decisions.
- Use `design-an-interface` when the user asks to explore or compare API/module interface
  alternatives.
- Use `domain-modeling` when introducing or clarifying business terminology, bounded concepts, or an
  architectural decision.
- Use `prototype` only for a deliberately throwaway experiment that answers a design question; do
  not treat a prototype as production implementation.
- Use `code-review` for reviews of changes since a commit, branch, tag, or merge-base. Use
  `resolving-merge-conflicts`
  for an in-progress merge or rebase.
- Use `research` when the task requires external technical or product research captured as a
  Markdown artifact in this repository.
- Use `setup-pre-commit` only when adding or changing Husky, lint-staged, or pre-commit automation.
  Use
  `git-guardrails-claude-code` only for Claude Code git-safety hooks.
- Use `github:github`, `github:gh-address-comments`, `github:gh-fix-ci`, and
  `github:yeet` only for the corresponding GitHub repository, review-comment, GitHub Actions, and
  publish-a-draft-PR workflows.
- Use `openai-docs` only for OpenAI/Codex product or API questions, and
  `find-skills` when the user asks to discover or install an additional skill.

For Spring Boot, JUnit, Javadocs, PostgreSQL, distributed-systems, and native changes, no
repository-specific skill is currently required. Follow Layers 1 and 2 directly: preserve the
Java/Spring/Javadoc/Flyway rules, explicitly assess cross-service reliability concerns, and run the
relevant Gradle, Flyway, or CMake validation. If multiple available skills match, use all of them in
the smallest order that covers the task.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in this repository's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

This repository uses the default five triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

This repository uses a single-context domain-documentation layout. See `docs/agents/domain.md`.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and
cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing
anything else.

Rules:

- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json
  exists. Use
  `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused
  concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep
  output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are
  not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph
  output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do
  not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
