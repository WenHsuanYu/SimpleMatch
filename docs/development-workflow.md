# SimpleMatch Development Workflow

This document defines the default development workflow for changes in this repository. It is intended to guide feature
work, refactoring, debugging, and cross-service changes so that implementation, documentation, and verification stay
aligned.

## 1. Start With The Request

Before writing code, identify the smallest concrete change that satisfies the request.

- Read the relevant requirements in README, tasks, and service docs.
- Identify the exact services, modules, and files affected.
- Write down acceptance criteria in plain language.
- Separate must-have behavior from nice-to-have follow-ups.
- If the change crosses service boundaries, data flow boundaries, or failure domains, treat it as a distributed-systems
  design problem, not just a code change.

## 2. Design The Smallest Safe Slice

Design the solution before implementation, but keep it small enough to deliver in one slice.

- Prefer vertical slices over broad platform refactors.
- Keep dependencies pointed inward and responsibilities narrow.
- Choose the simplest design that can satisfy the acceptance criteria.
- Document key decisions: ownership, idempotency, retries, consistency, and failure handling.
- For PostgreSQL changes, design the versioned Flyway migration, compatibility expectations, and verification plan
  before editing.
- If the change involves a distributed system concern, evaluate the relevant cloud design patterns explicitly, not
  implicitly.

### Required design principles

- Apply SOLID principles to classes, modules, and interfaces.
- Prefer single-responsibility components with clear boundaries.
- Use abstractions for policies and infrastructure details for implementation.
- Avoid adding generality before the third proven use case.

### Cloud design pattern checklist

For multi-service work, evaluate patterns such as:

- Outbox for reliable event publication.
- Retry for transient failures only, with bounded attempts.
- Circuit Breaker for failing dependencies.
- Bulkhead for isolating resources and blast radius.
- CQRS or Materialized View for read-heavy projections.
- Cache-Aside for Redis-first read paths.
- Sharding for routing or partitioned workloads.
- Gateway Routing for service boundary control.
- External Configuration Store for environment-specific settings.
- Saga or Choreography when a business transaction spans services.

Use patterns deliberately. Do not force a pattern just because it exists.

## 3. Write Tests Before Production Code

Follow test-first thinking for every non-trivial change.

1. Define the failing behavior first.
2. Write the smallest test that proves the behavior is missing.
3. Implement the minimum code required to make the test pass.
4. Refactor only after the test passes.

### Testing guidance for this repository

- Write focused unit tests for pure logic and domain rules.
- Write integration tests for Spring context, database, messaging, and service wiring.
- Write smoke or certification tests when a service has an external entry point.
- Keep each test focused on one behavior.
- Use descriptive test names that state the scenario and outcome.

### JUnit 5 best practices

- Use `@Test` for standard cases and `@ParameterizedTest` for data-driven cases.
- Follow the Arrange-Act-Assert structure.
- Use `@DisplayName` for human-readable test names.
- Keep tests independent and idempotent.
- Use `@SpringBootTest` only when a full application context is required.
- Prefer narrower test slices when the behavior can be isolated.
- Use Testcontainers when a real dependency is needed for trustworthy integration coverage.

## 4. Implement Using Repo Conventions

Implement the smallest production change that makes the tests pass.

### Spring Boot conventions

- Organize Java code by feature or domain, not by technical layer.
- Use constructor injection for required dependencies.
- Keep dependency fields `private final`.
- Prefer Java `record` for simple immutable carriers before reaching for Lombok.
- In `services/*`, Lombok may be used narrowly for Spring boilerplate such as required-args constructors or logging.
  Avoid broad annotations such as `@Data`, and keep domain, configuration, mutable, validation-heavy,
  normalization-heavy, custom-equality, or defensive-copy types handwritten.
- Use `@ConfigurationProperties` for type-safe configuration binding.
- Use profiles for environment-specific behavior.
- Use DTOs for boundary contracts instead of exposing persistence entities.
- Use Bean Validation for request validation.
- Prefer `@Transactional` on public concrete application or service methods that own a business transaction.
- Keep `JdbcTemplate` confined to thin repository adapters.
- If only a smaller section of a method should hold the transaction, keep expensive pre-work and external calls outside
  the transaction and use `TransactionTemplate` or another narrow programmatic boundary for the minimal transactional
  region.
- Use SLF4J with parameterized logging.

### PostgreSQL migration conventions

- Treat schema changes as first-class code changes, not operational afterthoughts.
- Use versioned Flyway migrations for PostgreSQL schema evolution.
- Follow the shared `simplematch.flyway-service` convention where the service already uses it.
- Keep migration SQL, repository code, service code, tests, and docs in the same change set.
- Do not reintroduce runtime migration code paths or one-off schema initialization logic when Flyway is the intended
  owner.
- Review SQL safety, compatibility, and maintainability before merging.
- Review query plans, indexing, and write/read amplification when a change affects performance-sensitive SQL.

### PostgreSQL skill routing

- Use `postgresql-code-review` for schema design, migration review, SQL safety, and PostgreSQL-specific maintainability.
- Use `postgresql-optimization` for indexing strategy, query structure, execution-plan-sensitive work, and PostgreSQL
  performance tuning.

### Java documentation conventions

- Document public and protected members with Javadocs.
- Document complex package-private or private members when they are not obvious.
- Start every Javadoc comment with a concise summary sentence ending in a period.
- Use `@param`, `@return`, and `@throws` where applicable.
- Use `@see` for related types or members.
- Use `{@code}` for inline code and `<pre>{@code ... }</pre>` for code examples.

### Code quality expectations

- Keep methods small and readable.
- Prefer explicit names over clever names.
- Remove duplication only after it is clearly proven.
- Keep classes focused and easy to test.
- Prefer stateless services and isolated side effects.

## 5. Validate In The Right Order

Run validation in the order that gives the fastest useful feedback.

### Java changes

1. Run the narrowest relevant unit or integration tests first.
2. Run the service test suite for the touched module.
3. Run `./gradlew -q staticAnalysis` for Java changes.
4. Run the relevant service smoke or certification test when available.

### PostgreSQL changes

1. Run migration-focused tests and any affected repository or service tests.
2. Run the relevant Flyway info, validate, or migrate task for the touched service.
3. Review the final SQL for PostgreSQL safety, compatibility, and maintainability.
4. Review indexing and execution-plan implications for performance-sensitive changes.
5. Run the relevant service verification when the schema change affects startup or integration behavior.

### Native changes

1. Run the narrowest native build or test target first.
2. Run the CMake preset build used by the repo.
3. Run the relevant native test preset or smoke check.

### Repository-wide validation commands

- Java module build or test examples:
    - `./gradlew :shared-java:simplematch-contracts:build`
    - `./gradlew :services:quickfix-gateway:test`
    - `./gradlew :services:account-service:test`
    - `./gradlew :services:risk-service:test`
    - `./gradlew :services:quickfix-gateway:certificationTest`
- Static analysis:
    - `./gradlew -q staticAnalysis`
    - This is the blocking repo-wide Error Prone gate for all Java modules; Checkstyle and SpotBugs remain enabled for
      the curated service/module set configured in Gradle.
- Flyway examples:
    - `./gradlew riskServiceFlywayInfo`
    - `./gradlew riskServiceFlywayMigrate`
    - `./gradlew riskServiceFlywayValidate`
- Local automation:
    - `bash scripts/install-git-hooks.sh`
    - Installed pre-commit hooks run targeted Gradle compile/checkstyle checks for staged Java or Gradle changes.
    - Installed pre-commit hooks also validate Flyway migration naming and directory placement.
- CI automation:
    - GitHub Actions enforces `./gradlew staticAnalysis` as the blocking repo-wide Error Prone gate, plus the Java test
      suite for Java-related changes. Local runs may add `-q` to reduce lifecycle noise while retaining actionable
      diagnostics.
    - GitHub Actions runs Flyway info and migrate tasks plus PostgreSQL smoke checks for Flyway-managed services.
- Native build:
    - `cmake --preset vcpkg`
    - `cmake --build --preset vcpkg -j`

If the change spans both Java and native code, validate both sides before closing the task.

## 6. Keep Documentation In Sync

Whenever paths, behavior, or boundaries change, update the documentation in the same change set.

- Update README when the structure or build entry points change.
- Update tasks.md when a milestone is completed or scope changes.
- Update docs/config.md when configuration keys or defaults change.
- Update docs/dependencies.md when build or dependency expectations change.
- Keep file and folder names consistent across docs and the actual repository layout.

## 7. Delivery Checklist

Before you consider a task done, confirm all of the following:

- The acceptance criteria are met.
- The relevant tests pass.
- Static analysis passes for Java changes.
- Documentation matches the actual implementation.
- Any new file or folder names are reflected in docs and build files.
- Follow-up work is explicitly recorded if it was intentionally deferred.

## 8. Practical Default Sequence

For most changes in this repository, use this order:

1. Analyze the request and identify affected modules.
2. Design the smallest safe slice.
3. Write tests or a test plan first.
4. Implement the change.
5. Add Javadocs where Java types are public, protected, or non-trivial.
6. Run targeted tests.
7. Run static analysis.
8. Run service or smoke tests.
9. Update docs and tasks.
10. Review the diff for consistency.

## 9. When To Escalate Design Work

Pause and do a deeper design pass when the change involves:

- A new service boundary.
- A new event stream or topic.
- Cross-service consistency or idempotency.
- Retry, circuit breaking, or bulkheading.
- Read-model projections or caching.
- New configuration surfaces or environment-specific behavior.
- A change that would cause shotgun surgery across many files.

In those cases, prefer a short design note or implementation plan before coding.
