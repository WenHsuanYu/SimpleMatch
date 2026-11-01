# Shell test framework migration specification

Status: owner-approved specification, after decisions Q1–Q13 and final review.
Date: 2026-10-29.
Source baseline: `7b9d2fd035632aa6797ad1d771a90c96b5062a87`.
This document specifies future behavior. It does not report an implemented migration or a runtime PASS.
GitHub Issues own execution status after publication.
Published specification: [GitHub Issue #196](https://github.com/WenHsuanYu/SimpleMatch/issues/196).

## Problem Statement

Shell currently owns environment operations, test scenarios, observations, verdicts, and parts of the verification framework.
These responsibilities increase the effort required to understand failures and change scenarios.
The baseline contains 170 tracked Shell files and 34,923 physical lines.
Replacing their language alone would retain unnecessary responsibilities and duplicate implementations.

Contributors need one local command that completes necessary verification without manual deployment to a shared Kubernetes environment.
CI must use the same test definitions, with explicit execution scopes and truthful result claims.
The migration must substantially reduce Shell while preserving necessary behavior and resource safety.

## Solution

Retire unnecessary checks before choosing replacements for necessary checks.
Use Gradle as the main entry point and build dependency owner.
Use Spring Boot test support and JUnit Jupiter for service scenarios and assertions.
Use Testcontainers for suitable dependency integration tests.
Keep a small Kubernetes suite for behavior that requires Kubernetes.
Keep limited environment operations behind a narrow interface.

Provide local complete verification and a disposable-cluster mode.
Use shared test tasks for focused business CI. Complete local verification remains required.
Evaluate Helm separately. Introduce rendered-manifest validation without requiring a repository-wide Helm conversion.

### Confirmed decisions

| Decision | Contract |
| --- | --- |
| Q1 | Move scenario and verdict ownership from Shell. Review unnecessary verification and substantially reduce Shell. |
| Q2 | Assess Helm separately and introduce it in stages. |
| Q3 | Target 80% reduction in both Shell file count and lines. Minimum acceptance is 70%. |
| Q4 | Retire obsolete or equivalent duplicate checks. Preserve necessary regression protection. |
| Q5 | Automate complete local verification. Use layered CI after push. |
| Q6 | Start from current full-local requirements. Review requirements rather than preserve every historical phase. |
| Q7 | Default local runs use a checked dedicated kind cluster with fresh test resources. Also support disposable clusters. |
| Q8 | Contributors explicitly invoke complete local verification. Do not add an expensive pre-push hook. |
| Q9 | Use Gradle and the existing Spring Boot test starter. Do not introduce Taskfile initially. |
| Q10 | Collect diagnostics before default cleanup. Retain local resources only through an explicit option. |
| Q11, amended 2026-10-29 | Assess requirement-preserving staging. Use focused business CI when complete scenarios exceed the hosted budget. Complete CI is optional. |
| Q12 | Deliver cohesive vertical slices under one specification. Delete replaced implementations within each slice. |
| Q13 | Record existing defects separately. Fix bounded blockers without weakening necessary checks. |

## User Stories

1. As a contributor, I want one complete local entry point, so that I do not manually deploy test services.
2. As a contributor, I want focused integration tasks, so that daily changes do not require a Kubernetes cluster.
3. As a contributor, I want named assertion failures, so that I can identify the failed requirement.
4. As a contributor, I want real dependency tests, so that mocks do not conceal database or messaging behavior.
5. As a contributor, I want Kubernetes tests for deployment behavior, so that container tests do not overstate coverage.
6. As a contributor, I want isolated run resources, so that previous test data cannot establish a new PASS.
7. As a contributor, I want an optional disposable cluster, so that I can reproduce cluster-specific failures.
8. As a contributor, I want automatic diagnostics and cleanup, so that failed runs remain understandable without accumulating resources.
9. As a contributor, I want explicit resource retention, so that I can inspect a failed local environment.
10. As a maintainer, I want ownership checks, so that cleanup preserves unrelated resources and canonical clusters.
11. As a maintainer, I want a requirement disposition ledger, so that deletions have reviewable reasons.
12. As a maintainer, I want replaced code removed with its replacement, so that two frameworks do not remain authoritative.
13. As a maintainer, I want reproducible reduction measurements, so that language relocation cannot satisfy the goal.
14. As a reviewer, I want negative-case equivalence checks, so that a migration cannot pass by weakening assertions.
15. As a reviewer, I want existing defects recorded, so that a migration does not hide failures or expand without bounds.
16. As a CI user, I want the same test definitions locally and remotely, so that workflow copies cannot diverge.
17. As a CI user, I want explicit business-scenario coverage, so that focused CI does not imply complete certification.
18. As a maintainer, I want versioned manifest checks, so that deployment errors fail before expensive runtime preparation.

## Implementation Decisions

### Responsibility owners

| Responsibility | Owner | Required limit |
| --- | --- | --- |
| Build dependencies and task selection | Gradle | Do not recreate a general phase planner or historical-result cache. |
| Spring service tests | Existing Spring Boot test starter and Jupiter | Prefer narrow tests. Start a full Spring context only when necessary. |
| Pure evidence evaluation | Small Java evaluator with Jupiter tests | No Spring context, Docker, or cluster required for pure evaluation. |
| Dependency containers | Testcontainers fixtures | One lifecycle owner per resource. Preserve Flyway and service configuration contracts. |
| Deployed business scenarios | Jupiter tests using actual service interfaces | Pod readiness alone cannot establish business success. |
| Kubernetes operations | Narrow environment adapter | Bounded commands, explicit context, ownership, diagnostics, and cleanup. |
| Manifest structure | Kubeconform over rendered manifests | Pin Kubernetes and schema inputs. Reject unexpected missing schemas. |
| Chart checks after Helm adoption | Helm lint and rendered-manifest checks | Do not duplicate complete business scenarios in chart hooks. |
| CI execution | GitHub Actions calling shared tasks | No separate scenario logic in workflow command blocks. |
| Native verification | Existing native build and test tools | Preserve required native coverage. Do not translate native tests into Java. |

The environment adapter may call established command-line tools.
It must not become a Java wrapper around the complete existing Shell framework.
Use a small shared command facility only for necessary process timeouts, exit status, and diagnostic capture.
Keep scenario decisions and business assertions outside that facility.
Do not add a generic chaos platform, plugin framework, or second orchestration language.

### Spring and Jupiter dependencies

Spring service modules already receive `spring-boot-starter-test` through the common service convention.
Use that dependency entry point and its managed assertion, mocking, JSON, and Spring test libraries.
Add Testcontainers modules only for fixtures that require them.
Do not add competing versions of libraries already managed by the project.

The checked Query lock resolves Spring Boot 4.1.0 and JUnit Jupiter 6.0.3.
Therefore, this specification uses “JUnit Jupiter” rather than requiring a downgrade to JUnit 5.
The earlier JUnit 5 tool list described the intended testing model, not a version migration requirement.
Standalone evaluators may retain the existing lightweight Java convention and managed Jupiter dependencies.
No dependency upgrade or downgrade is required solely by this specification.

### Proposed contributor interface

The following task names are proposed public interfaces, not currently implemented commands.
Keep existing focused module tasks available where they remain useful.
Resolve any task-name collision during the first slice without adding duplicate meanings.

| Proposed task | Contract |
| --- | --- |
| `manifestCheck` | Render supported profiles and check schemas plus necessary project-specific constraints. |
| `integrationTest` | Run selected real-dependency integration suites without Kubernetes. |
| `kubernetesTest` | Prepare the required environment and run the selected Kubernetes scenarios. |
| `verifyAll` | Execute every retained requirement in the approved complete verification ledger. |
| `cleanupVerification` | Clean one explicitly identified run after validating its ownership. |

The complete entry point must perform these operations without manual deployment steps:

1. Validate tools, source, required inputs, daemon identity, cluster identity, and resource capacity.
2. Select a new run identity and reject an existing evidence destination before resource mutation.
3. Build required artifacts through existing build tools and normal caches.
4. Prepare dependency containers, images, and the selected cluster mode.
5. Deploy resources in the required migration, topic, Connect, Open Barrier, and workload order.
6. Run required tests and record named results.
7. Collect bounded diagnostics before resource removal.
8. Restore changed shared state and clean owned resources, unless explicit local retention applies.
9. Publish the aggregate result with cleanup and restoration outcomes.

Exact properties for cluster mode, scenario selection, run identity, and retention belong to the first implementation slice.
They must have one documented meaning across local and CI calls.
Automatic setup covers test resources, not installation of host software or acquisition of credentials and approved business inputs.
Missing prerequisites must fail before deployment and identify the required remedy.
Live verification tasks must execute again on repeated invocation.
They must not obtain PASS from Gradle task avoidance, build-cache output, or earlier runtime evidence.
Normal compilation and image-build caches remain permitted.

### Local and disposable cluster modes

Default local mode uses an explicitly identified dedicated kind cluster.
Validate that cluster before use. Create it automatically when the designated cluster is absent and prerequisites permit creation.
Fresh run identities must isolate namespaces, dependency data, and other disposable resources.
Namespace isolation alone does not isolate node failure, cluster-scoped resources, or shared dependency mutations.

Scenarios that affect shared state must prove restoration or use a disposable cluster.
The complete entry point must select that isolation automatically where required.
Serialize scenarios that share mutable environment state initially.
Reject concurrent use of the same mutable local environment unless isolation is established.

Disposable mode creates a uniquely owned cluster and removes it after diagnostics.
CI uses disposable mode and does not offer interactive resource retention.
Default local cleanup preserves the dedicated cluster and unrelated resources.
Validate exact ownership before deleting namespaces, containers, volumes, images, or clusters.
Never use broad prune operations or delete an older retained run merely because a new run starts.

### Failure, retention, and evidence

Preserve current source identity, applicable image identity, and run identity in results.
Collect only diagnostics with a current requirement or consumer.
Redact credentials and secrets from reports and command logs.
Do not recreate historical PASS lookup, resume, lineage caches, or a content-addressed evidence platform.

| Condition | Required outcome |
| --- | --- |
| Required assertions, restoration, and cleanup succeed | Complete PASS only when every required item executed successfully. |
| Selected profile omits requirements | Report PARTIAL. Omitted items have no PASS. |
| Required test is skipped, disabled, undiscovered, or has no evidence | Reject complete PASS. Identify the unmet requirement. |
| Setup, assertion, or observer fails | Preserve the primary failure. Collect diagnostics and attempt owned cleanup. |
| Cleanup or restoration fails | Reject aggregate success, including when assertions passed. Report both failures when applicable. |
| Diagnostics fail | Report collection failure. Preserve the original result and still attempt cleanup. |
| User explicitly retains local resources | Report retention and exact cleanup instructions. Retention cannot waive required restoration. |
| Process or runner terminates abruptly | Do not infer success. Detect residual ownership on later preflight and require explicit scoped recovery. |

Use Jupiter XML and normal test reports for named test outcomes.
Keep a compact aggregate summary for requirement coverage, environment identity, restoration, and cleanup.
Reports from pure retained-evidence evaluators cannot prove current environment health by themselves.
Focused diagnostics must keep their limited claims and cannot upgrade an earlier aggregate result.
Historical reports remain unchanged.
If a report schema changes, migrate current producers and readers together within the affected slice.

### Requirement disposition and existing defects

Create a ledger before deleting verification behavior.
Each entry must name the requirement, authority, current callers, current implementation, disposition, replacement, evidence, and deletion condition.
Inventory direct shell invocation, sourced helpers, embedded interpreters, workflows, build tasks, and documented manual entry points.
Absence from CI is not sufficient proof that a script is unused.

Use these dispositions:

- Keep a check when it protects a current requirement at an appropriate test seam.
- Merge checks when one test proves the same requirement under equivalent conditions.
- Replace a check when a standard tool or framework owns its complete necessary responsibility.
- Retire a check when its requirement no longer applies or an accepted equivalent makes it redundant.

Do not retire a regression solely because its original defect was fixed.
Do not preserve checks that exist only for retired machinery.
Record uncertain requirements as unresolved ledger entries. Such entries block deletion, not unrelated work.

Record existing failures separately from migration regressions.
Fix an existing defect within a slice only when it blocks that slice and the repair remains bounded.
Otherwise, track a separate defect and its blocking relationship.
Required unresolved failures prevent complete verification acceptance.
Never convert them into skips, weaker assertions, or undocumented exclusions.

### Helm and schema validation

Start schema validation from the current rendered Kustomize profiles.
Kubeconform does not replace project-specific configuration or business constraints.
Pin the validator, Kubernetes target, and schema source or content identity.
Missing custom-resource schemas must fail unless a specific reviewed exception names the uncovered resource and remaining validation.

Assess Helm by the deployment responsibilities it can remove.
If adopted, migrate a bounded deployment slice and delete its obsolete rendering path in the same delivery.
Add strict chart lint and final rendered-manifest validation for supported values profiles.
Preserve migration ordering, image identity, resource ownership, and runtime installation checks.
Helm is not a prerequisite for the Shell reduction acceptance gate.
Do not introduce Chainsaw, Chaos Mesh, or Toxiproxy without a demonstrated requirement and a separate bounded decision.

### CI and execution budgets

Push-triggered CI runs fast checks, relevant integration suites, and selected kind scenarios through shared tasks.
Changes to shared test infrastructure must select all affected suites, not only the directly changed service.
CI runs selected business scenarios in bounded jobs. Manual workflows may select the same focused suites.
No initial schedule or expensive pre-push hook is required.

Use GitHub-hosted runners as the initial target.
Measure CPU, memory, disk, duration, and image-transfer requirements for each selected CI job.
Set finite task, scenario, readiness, diagnostics, cleanup, and job timeouts from those measurements.
Do not fabricate time or capacity guarantees in this specification.
Assess whether staging preserves all requirements within the standard hosted budget.
The S0 profile still needs fifteen concurrent Matching owners, exceeding that declared budget.
Use focused business CI instead of requiring complete CI or acquiring larger runner capacity.
Keep complete local verification and report CI scope explicitly.
Do not substitute an unapproved self-hosted deployment.

## Testing Decisions

Test through service behavior, protocol results, retained-evidence evaluation, and resource lifecycle outcomes.
Avoid tests that only assert internal helper call order or duplicate parser-library behavior.
Use the existing Query HTTP/PostgreSQL fixtures and Query evidence evaluator as prior art.
Preserve necessary native, Flyway, security, messaging, and recovery coverage in the requirement ledger.

| Test seam | Required evidence |
| --- | --- |
| Pure evaluator | Accepted evidence passes. Missing, inconsistent, and requirement-specific invalid evidence fails with a named reason. |
| HTTP and dependency integration | Actual service behavior against appropriate real dependencies, including negative authorization and data-integrity cases. |
| CDC and messaging | Durable event identity, observation baseline, required delivery, and failure behavior. Readiness alone is insufficient. |
| Kubernetes scenario | Actual deployment or recovery behavior plus restoration and ownership evidence. |
| Environment lifecycle | Partial setup failure, timeout, cancellation, cleanup failure, explicit retention, and unrelated-resource preservation. |
| Manifest checks | Supported rendered profiles pass. Invalid schema and required project-constraint examples fail before deployment. |
| Complete entry point | Missing tests, skipped requirements, stale results, and incomplete cleanup cannot produce complete PASS. |

Use existing evidence fixtures for migration equivalence where they represent current requirements.
Include negative cases. Two implementations passing only the happy path is insufficient.
Old and new verdicts may temporarily evaluate the same captured evidence during development.
Do not run destructive old and new scenarios concurrently against the same environment.
Each delivered replacement removes its obsolete implementation and dedicated tests.

Run focused checks during each slice, then the relevant complete module or service suite.
Run repository static analysis for Java changes and applicable native or Flyway checks.
Follow deployment preflight and lesson requirements before any live environment test.
Batch coherent scenario changes before the final complete local run.
Do not start a full deployment for every child issue.

## Acceptance Criteria

| ID | Acceptance requirement |
| --- | --- |
| AC-01 | Every baseline Shell file has a disposition. Every retained verification requirement maps to its authoritative test or tool. |
| AC-02 | Final tracked Shell count is at most 51 files and 10,476 lines. Aim for at most 34 files and 6,984 lines. |
| AC-03 | Review confirms no concealed Shell relocation and no permanent duplicate scenario implementation. |
| AC-04 | The complete local entry point executes all retained requirements without manual deployment after prerequisites are available. |
| AC-05 | Repeated complete invocations execute live checks anew and isolate run data. Test selection cannot silently weaken complete scope. |
| AC-06 | Default dedicated-cluster mode and disposable mode satisfy ownership, failure, retention, and cleanup contracts. |
| AC-07 | Named reports identify assertion failures. Aggregate reports distinguish partial scope, failures, retention, and complete success. |
| AC-08 | Spring service tests use the existing starter. Dependency fixtures and pure evaluators avoid unnecessary infrastructure. |
| AC-09 | Supported manifests pass versioned schema checks and necessary project constraints. Runtime checks cover schema limitations. |
| AC-10 | Selected business CI calls shared tasks and passes with recorded coverage, capacity, and duration. Complete CI is optional. |
| AC-11 | A final source-aligned complete local run passes. Required unresolved defects prevent acceptance. |
| AC-12 | Current docs, callers, evidence readers, and owning issues match the delivered interfaces and retired behavior. |

### Reduction measurement

The accompanying baseline records every tracked path ending in `.sh` at the source commit.
Count physical lines as LF bytes plus one for a nonempty final line without LF.
Include comments and blank lines. Exclude untracked files and generated output.
Apply the same method to all tracked `.sh` files at the final delivery commit, including newly added files.
Do not reduce the denominator after individual deletions or exclude retained infrastructure helpers from the final total.

Reduction equals one minus the final total divided by the baseline total.
Both file count and line count must independently meet the 70% minimum.
An 80% reduction is the design target. A reduction above 90% is acceptable when requirements remain satisfied.
Report unrelated concurrent changes separately. They must not conceal added complexity or receive unearned migration credit.

Audit shell content in extensionless executables, workflow blocks, build files, Java strings, and other interpreters separately.
Report added and removed Java, YAML, Ruby, Python, configuration, and test support alongside the Shell totals.
Do not require those languages to meet an arbitrary line ratio.
Explain which responsibilities disappeared and why remaining support code is necessary.
Reject minification, concatenation, and renamed scripts as evidence of reduced responsibility.

## Delivery Slices

These slices define work boundaries and blocking relationships, not completed tickets.
The first slice must produce a credible path to the minimum reduction before broad migration begins.
If the inventory cannot support that path, report the concrete retained responsibilities before revising scope.

| Slice | Depends on | Deliverable and exit condition |
| --- | --- | --- |
| S0: requirements and budget | Spec approval | Complete disposition ledger, caller inventory, defect inventory, resource estimate, and deletion budget meeting both minimums. |
| S1: one complete migration | S0 | Replace a bounded verdict or behavior seam. Update callers and delete its obsolete implementation and contract tests. |
| S2: dependency scenarios | S1 | Move selected HTTP, database, and messaging scenarios to existing test conventions. Remove their obsolete runners and duplicate fixtures. |
| S3: Kubernetes lifecycle and scenario | S1 | Deliver the environment interface through one real scenario in both cluster modes. Prove failure cleanup and remove its old path. |
| S4: manifest and deployment checks | S0 | Validate rendered profiles with Kubeconform. Preserve domain constraints and retire equivalent ad hoc checks. Record Helm assessment. |
| S5: remaining scenarios and complete entry | S2, S3, S4 | Migrate remaining retained requirements. Replace the aggregate entry point and remove superseded orchestration. Meet the deletion budget. |
| S6: CI and final acceptance | S5 | Enable focused business CI after staging assessment. Complete local verification, AC-01 through AC-12, and publish final measurements. |

The Market Data evidence verdict is a candidate for S1, not a mandatory isolated Java module.
Choose its location using existing conventions and ownership.
Its small size proves the migration method but cannot establish the overall reduction target.
CDC verification and critical-consumer observation are larger candidates for later slices.
Preserve their event, recovery, and isolation semantics rather than translating their Shell structure.

Each implementation ticket must identify requirements, affected modules, red/green tests, deletions, caller updates, and its blocking edges.
Keep commits cohesive. Integrate one responsibility at a time.
Temporary compatibility entry points must contain no duplicated verdict logic and must name their removal slice.
Do not leave a replacement and its obsolete framework both active after that slice completes.

If a replacement fails acceptance, keep the old authoritative path until the slice is corrected.
If a delivered slice needs rollback, revert its callers, replacement, and deletions together.
Never rewrite historical evidence to represent the rolled-back or replacement implementation.

## Out of Scope

- Mandatory repository-wide Helm conversion before test migration.
- A new Taskfile, generic orchestration engine, or historical-result reuse platform.
- Production HA, endurance, or capacity certification beyond retained local requirements.
- Unrelated business changes or broad repairs of existing defects.
- Changes to immutable static-analysis rules or weaker quality thresholds.
- Automatic host software installation, credential acquisition, or broad resource cleanup.
- Fixed schedules, expensive pre-push hooks, and unapproved runner procurement.

## Further Notes

### Current-source references

These links support the baseline assessment. Future implementation paths can change within reviewed slices.

- [Frozen Shell inventory](shell-test-reduction-baseline.json).
- [Tool feasibility research](../research/shell-test-framework-feasibility.md).
- [Existing workflow comparison](certification-user-workflow-options.md).
- [Current execution contract](../verification/local-certification-phase-dag.md).
- [Complete local runbook](../verification/local-production-like-certification.md).
- [Deployment preflight and lessons](../agents/deployment-test-lessons.md).
- [Development workflow](../development/development-workflow.md).
- [Common Spring service convention](../../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchSpringServicePlugin.kt).
- [Query test tasks](../../services/query-service/build.gradle.kts) and [dependency lock](../../services/query-service/gradle.lockfile).
- [Query evidence evaluator](../../tools/query-evidence-verifier/README.md).
- [Market Data verdict](../../scripts/end-to-end/market-data/lib/verdict.sh).
- [CDC verifier](../../scripts/lib/cdc-verifier.sh).
- [Current phase declarations](../../scripts/lib/local-certification-phase-graph.sh).
- [Existing lifecycle CI](../../.github/workflows/ci-local-resource-lifecycle.yml).

### Approval and handoff

Q1–Q13 establish the agreed direction. The owner approved the complete specification on 2026-10-29.
Publish the specification to the project issue tracker and create bounded implementation tickets with native blocking links.
Approval authorizes publication and task decomposition. It does not report implementation completion.
Do not claim this specification, research, or static document checks establish runtime feasibility measurements or certification PASS.

### Published implementation issues

These links record publication, not completion. GitHub owns current status and native blocking relationships.

| Slice | Issue | Blocked by |
| --- | --- | --- |
| S0 | [#197](https://github.com/WenHsuanYu/SimpleMatch/issues/197) | None |
| S1 | [#198](https://github.com/WenHsuanYu/SimpleMatch/issues/198) | #197 |
| S2 | [#199](https://github.com/WenHsuanYu/SimpleMatch/issues/199) | #198 |
| S3 | [#200](https://github.com/WenHsuanYu/SimpleMatch/issues/200) | #198 |
| S4 | [#201](https://github.com/WenHsuanYu/SimpleMatch/issues/201) | #197 |
| S5 | [#202](https://github.com/WenHsuanYu/SimpleMatch/issues/202) | #199, #200, #201 |
| S6 | [#203](https://github.com/WenHsuanYu/SimpleMatch/issues/203) | #202 |
