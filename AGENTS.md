# SimpleMatch Repo Instructions

These instructions apply to the whole repository.

## Layer 1: Non-negotiable guardrails

- Read [docs/development-workflow.md](docs/development-workflow.md) before making any change.
- Read [badCodeSmell.md](badCodeSmell.md) before writing or editing code, and treat it as an avoid-list for code smells during design, implementation, and refactoring.
- Identify the affected files, services, and modules before editing.
- Write acceptance criteria first, then choose the smallest safe slice.
- Keep code, tests, documentation, tasks, and configuration aligned in the same change.
- Do not widen scope without a concrete reason.
- PostgreSQL schema changes must use versioned Flyway migrations via the shared `simplematch.flyway-service` convention; do not reintroduce runtime migration or ad hoc schema initialization flows.
- For Java changes, require tests and Javadocs where members are public, protected, or non-obvious.
- For Java, prefer records for simple immutable carriers. In `services/*`, Lombok may be used narrowly to remove Spring boilerplate such as required-args constructors or logging, but avoid broad annotations such as `@Data` and keep domain, config, mutable, validation-heavy, normalization-heavy, custom-equality, or defensive-copy types handwritten.
- For Spring JDBC code, prefer `@Transactional` on public concrete application or service methods that own a business transaction. Keep `JdbcTemplate` in thin repository adapters, and when only part of a method should be transactional, use `TransactionTemplate` or another narrow programmatic boundary instead of widening the full method or moving transaction ownership into repositories.
- If the task matches a workflow skill, treat that skill as required guidance, not optional reading.
- After completing any code task, run a concise bad-smell avoidance checklist against [badCodeSmell.md](badCodeSmell.md) and call out any remaining risks.

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
10. For PostgreSQL changes, run the relevant Flyway task or migration test and review SQL/index/query implications.
11. Update README, tasks.md, and related docs.
12. Review the diff for consistency.
13. Finish with a bad-smell avoidance checklist against [badCodeSmell.md](badCodeSmell.md).

When a change crosses a service boundary, messaging boundary, or deployment boundary, consult the workflow doc and the relevant cloud design patterns before coding.

## Layer 3: Skill routing

- Use `solid` for architecture, refactoring, debugging, review, and other non-trivial code changes.
- Use `cloud-design-patterns` for distributed-system, messaging, reliability, scaling, and deployment decisions.
- Use `java-springboot` for Spring Boot application code, configuration, controllers, services, and persistence wiring.
- Use `java-junit` for unit, integration, parameterized, and Spring Boot tests.
- Use `java-docs` for Javadocs on public/protected members and any non-obvious Java code.
- Use `postgresql-code-review` for PostgreSQL schema, migration, SQL safety, and maintainability review.
- Use `postgresql-optimization` for PostgreSQL query design, indexing, and performance-sensitive SQL changes.
- If a task spans multiple categories, consult all relevant skills before editing.
