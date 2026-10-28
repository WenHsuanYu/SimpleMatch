# Certification reuse alternatives

Research date: 2026-10-28. This note proposes a direction for C, the certification planning and evidence tests.
It does not change the current reuse policy.

## What the existing tests protect

The command name does not determine a test's value.
A test can execute the planner, change an input, and check that the next decision requires execution.
It can also inspect a command log to check that a failed phase prevents subsequent commands.
These are behavior tests, even when `grep` checks their outputs.
The current [incremental tests](../../scripts/test-local-certification-incremental.sh) and [reuse safety tests](../../scripts/test-local-certification-reuse-safety.sh) contain these cases.

Searching runner source for a function name proves only that the text exists.
Counting source lines cannot prove correct recovery or lower maintenance cost.
Review those checks separately from tests of decisions, failure status, and cleanup.

The current [phase declarations](../../scripts/lib/local-certification-phase-graph.sh) already require fresh execution for live Compose, Kubernetes, and fault evidence.
They permit content-addressed reuse for static checks and local image builds.
Registry publication requires revalidation.
The [reuse specification](../verification/local-certification-phase-dag.md) also rejects a previous PASS as sufficient evidence for a new runtime.

## What other projects do

The examples show concrete ways to divide responsibilities.
They do not establish that these projects avoid all test-maintenance problems.

| Project | Verified approach | Practical limit |
| --- | --- | --- |
| Envoy | Bazel caches successful test results. Its contributor guide documents `--cache_test_results=no` to force execution. | The build tool owns generic cache behavior. Tests still define project-specific correctness. [Source](https://github.com/envoyproxy/envoy/blob/main/bazel/README.md) |
| Kubebuilder | Ginkgo and envtest support controller tests. Its contributor workflow also uses kind for E2E tests and requires temporary-resource cleanup. | envtest starts etcd and the API server without kubelet or controller-manager. It cannot establish actual Pod recovery. [Contributor workflow](https://github.com/kubernetes-sigs/kubebuilder/blob/master/CONTRIBUTING.md), [envtest scope](https://book.kubebuilder.io/reference/envtest) |
| Apache Spark Kubernetes Operator | CI downloads a previously built operator image, loads it into Minikube, and executes Chainsaw E2E tests. | The workflow still contains shell and jq for setup and checks. The tool does not remove every orchestration responsibility. [CI workflow](https://github.com/apache/spark-kubernetes-operator/blob/main/.github/workflows/build_and_test.yml) |

Gradle can reuse task outputs when declared inputs match.
Missing inputs can produce incorrect cache hits.
Its documentation advises against incremental execution for nondeterministic tasks. [Build cache](https://docs.gradle.org/current/userguide/build_cache.html), [incremental build](https://docs.gradle.org/current/userguide/incremental_build.html)

Testcontainers manages disposable integration-test services and integrates with JUnit. [Spring Boot integration](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
Its experimental reusable-container feature retains containers between executions and is unsuitable for CI. [Reuse documentation](https://java.testcontainers.org/features/reuse/)
Reusing a container does not establish that an earlier PASS remains valid.

## Alternatives for SimpleMatch

These trade-offs are engineering assessments based on the current responsibilities.
This research did not measure runtime or storage savings.

| Alternative | What it can replace | Trade-off |
| --- | --- | --- |
| Existing Gradle and container-build caches, with fresh live checks | Custom decisions about unchanged deterministic work. Static checks can run again when their cost is small. | Lowest additional tooling cost. Some checks repeat. Complete input declarations still matter for cached tasks. |
| A small custom reuse layer for expensive artifacts | Broad reuse of cheap static test results. Keep only cases with a measured benefit beyond existing caches. | Retains some identity and invalidation tests. Reduces flexibility but can reduce policy surface. |
| Java planner and JUnit | Bash implementation and test plumbing for retained decisions. | Type checking and fixtures improve maintainability. Custom fingerprints, lineage, and external-state rules still require implementation and tests. |
| Bazel | Generic multi-language build dependencies and cache decisions. | Requires a build-system migration alongside Gradle and CMake. It cannot infer mutable external cluster state from source files. |
| Chainsaw for Kubernetes scenarios | Repeated Kubernetes-object checks in suitable scenarios. | Adds a test language and runner. Domain evidence, provenance, and resource ownership still need explicit rules. It does not replace C's cache policy. |

Bazel requires tests to declare their dependencies for reproducible results. [Test environment contract](https://bazel.build/reference/test-encyclopedia)
Therefore, changing build tools cannot make an undeclared external environment safe to cache.
Chainsaw supports declarative tests of Kubernetes objects. [Project scope](https://github.com/kyverno/chainsaw/blob/main/README.md)

## Proposed direction

Keep fresh live checks, source and image identity, explicit partial-run status, and owned-resource cleanup.
These requirements protect current observations and safe operation.

Use existing build caches for compilation and image construction.
Prefer rerunning cheap static checks over preserving a separate historical PASS for each check.
Retain custom artifact reuse only where measurements show a material benefit beyond those caches.
Treat continuation of an existing namespace as a separate operation with current ownership checks.

Moving a planner to Java preserves its maintenance obligations unless its responsibilities also become smaller.
The first Java migration should therefore remain Query evidence evaluation, which already has a clear file-based interface.
Evaluate a Kubernetes test runner later against one complete scenario, rather than adding it to reduce shell statistics.

Any later reduction of C must update the phase declarations, fingerprints, retained evidence, tests, and reuse specification together.
This research note does not authorize treating skipped or historical runtime checks as current PASS evidence.
