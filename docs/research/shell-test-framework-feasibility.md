# Shell test framework feasibility research

Status: research notes for discussion, not an approved specification.
Sources checked: 2026-10-29. No deployment or runtime test was executed for this research.

## Scope and acceptance criteria

This note checks tool capabilities against official documentation.
It supports a later specification for moving test behavior from Shell into a test framework.
It does not approve a repository migration or select dependency versions.

The research must establish the following facts:

1. Which tools can own assertions, dependency containers, Kubernetes deployment, and manifest checks.
2. Which cleanup and concurrency guarantees require explicit design.
3. Which static checks still need runtime verification.
4. Which decisions the implementation specification must settle.

## Findings

### JUnit 5 and Testcontainers

JUnit Jupiter supports lifecycle callbacks, assertions, parameterized tests, tags, and extensions.
Tags select tests during discovery and execution.
Jupiter runs tests sequentially by default, with explicit configuration for parallel execution.
These features support named scenarios and independent verification logic.
They do not define a deployment phase graph or application-specific evidence rules.
The reference is the versioned JUnit 5 guide, not a recommendation to change repository dependencies.
See the [JUnit 5.13.4 user guide](https://docs.junit.org/5.13.4/user-guide/).

The Testcontainers Jupiter extension starts and stops fields annotated with `@Container`.
Static fields share a container across methods within a test class.
Instance fields receive a container lifecycle for each test method.
The extension documents sequential execution support and warns that parallel execution is unsupported.
See [Testcontainers Jupiter integration](https://java.testcontainers.org/test_framework_integration/junit_5/).

Testcontainers requires a Docker API compatible runtime.
Runtime availability and connection configuration remain environment prerequisites.
See [Testcontainers runtime requirements](https://java.testcontainers.org/supported_docker_environment/).

Design inference: use Testcontainers for dependency integration tests that need PostgreSQL, Kafka, or similar dependencies.
Use kind for tests that need Kubernetes behavior.
Define one lifecycle owner for each resource.
Do not assume that a Testcontainers lifecycle callback owns a separately created kind cluster or Helm release.
Initially serialize tests that share a cluster, namespace, or dependency state.
Parallel execution requires an explicit isolation design and verification.

### kind and CI lifecycle

kind supports named clusters, pinned node images, readiness timeouts, image loading, cluster deletion, and log export.
The documentation recommends stable tagged releases for CI.
See [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/).

Design inference: a small environment adapter can create a run-specific cluster and expose its kubeconfig to tests.
The adapter still needs bounded commands, ownership checks, image identity checks, diagnostics, and cleanup.
A framework changes where these requirements live.
It does not remove them.

GitHub-hosted runners normally provide a new VM for each job, with an exception for single-CPU runners.
Self-hosted runners do not require a clean instance for every job.
See [GitHub-hosted runners](https://docs.github.com/en/actions/concepts/runners/github-hosted-runners) and
[self-hosted runners](https://docs.github.com/en/actions/concepts/runners/self-hosted-runners).

GitHub Actions supports steps with `always()` for logs and cleanup after earlier failures or cancellation.
The documentation warns against tasks that can hang and recommends `!cancelled()` for ordinary success-independent execution.
See [workflow status expressions](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions#status-check-functions).

Design inference: bound diagnostic and cleanup steps explicitly.
A stopped runner or killed process can prevent application callbacks from executing.
Persistent development machines and self-hosted runners need an ownership-based recovery procedure.
Collect evidence before deleting the resources that produced it.

### Helm lint, rendering, and chart tests

`helm lint` checks chart structure and reports errors and warnings.
`--strict` makes warnings fail the command.
`--kube-version` supplies the Kubernetes version for capabilities and deprecation checks.
See [Helm 3 lint reference](https://helm.sh/docs/v3/helm/helm_lint/).

`helm template` renders templates locally.
Local rendering does not perform the server-side checks that establish whether the cluster supports an API.
See [Helm template reference](https://helm.sh/docs/helm/helm_template/).

`helm test` runs test workloads defined by the chart and marked with the test hook annotation.
The workload must exit successfully for Helm to consider the test successful.
See [Helm chart tests](https://helm.sh/docs/topics/chart_tests/).

Design inference: chart lint and rendered-manifest checks are useful early gates.
Keep business assertions in one authoritative test suite.
Chart smoke tests can verify installation without duplicating complete trading or recovery scenarios.
Helm adoption also needs a deployment compatibility decision if the repository currently owns manifests through another renderer.

### Kubeconform

Kubeconform validates Kubernetes manifests against schemas.
It supports an explicit Kubernetes version, strict validation, local schema locations, and JUnit output.
Its default schema does not include arbitrary Custom Resources.
Those resources require suitable additional schemas.
`-ignore-missing-schemas` skips resources instead of proving their validity.
Kubeconform excludes extra server-side validation that Kubernetes controllers perform outside the OpenAPI specifications.
See the [Kubeconform README](https://github.com/yannh/kubeconform).

Design inference: validate the final rendered manifests for each supported values or overlay profile.
Pin the Kubernetes version and schema inputs.
Treat unexpected missing schemas as failures.
Review any explicit schema exceptions individually.
The specification should preserve a runtime installation check for admission, storage, networking, scheduling, and application readiness.

### Task and Chainsaw

Task registers a deferred command only when execution reaches its `defer` entry.
An earlier command or dependency failure leaves that cleanup unregistered.
Deferred commands run in reverse registration order.
See [Task errors and cleanup](https://taskfile.dev/docs/guide/errors-and-cleanup).

Design inference: a `create cluster` command followed by `defer cleanup` does not cover partial creation failures.
Register an idempotent cleanup operation before creation, after validating the intended resource ownership.
Ensure diagnostics execute before deletion.
Task can provide command names without becoming another owner of test verdicts or certification semantics.

Chainsaw provides declarative tests for Kubernetes operators, controllers, and other Kubernetes objects.
See the [Chainsaw project README](https://github.com/kyverno/chainsaw).

Design inference: Chainsaw and Kubeconform cover different concerns.
Kubeconform checks manifest schemas before deployment.
Chainsaw can express behavior against Kubernetes objects during a runtime test.
Add Chainsaw only when object-focused scenarios justify a second test language alongside Java.

## Inputs for the specification

The specification must name each test layer and its authoritative verdict owner.
It must map the current scenarios, evidence, and cleanup requirements to that owner.
It must identify the first migration slice and the corresponding obsolete Shell logic.

The specification must also settle the following decisions:

1. The first behavior to migrate and the acceptance evidence required for equivalence.
2. The deployment renderer and whether Helm conversion is a separate workstream.
3. The cluster lifecycle owner and the policy for retained development environments.
4. The supported runner environment, resource budget, and initial concurrency limit.
5. The handling of failed setup, failed assertions, timeouts, cancellation, and interrupted cleanup.
6. The report contract for selected, omitted, skipped, failed, and completed scenarios.

No runtime or certification PASS follows from this research.
