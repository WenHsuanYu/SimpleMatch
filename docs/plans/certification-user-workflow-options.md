# Certification options for contributors

Research date: 2026-10-28.
This comparison starts with the contributor's task: run a scenario, identify its failure, and clean its test resources.
It compares contributor workflows for SimpleMatch. The category C implementation adopts fresh execution with ordinary build-tool caches.

## Reduction before replacement

Keep a helper only when it supports a current requirement or makes a necessary task simpler.
Delete probes that only helped diagnose a resolved defect.
Preserve a regression test when it protects a current requirement.
Do not translate every old helper into Java or YAML.

Judge a replacement by the responsibility it eliminates, including its configuration, tests, reports, and contributor instructions.
A lower shell line count does not establish a lower maintenance cost.
No additional tool is necessary to delete an unnecessary feature.

## Existing Spring test support

The common service convention already declares `spring-boot-starter-test` as a test dependency.
All seven Spring services apply that convention. Query's dependency lock records version `4.1.0`. [Common convention](../../build-logic/src/main/kotlin/com/simplematch/gradle/SimpleMatchSpringServicePlugin.kt), [Query build](../../services/query-service/build.gradle.kts), [Query lock](../../services/query-service/gradle.lockfile)

Query already uses `@SpringBootTest` and MockMvc for authenticated HTTP behavior.
It also uses `WebApplicationContextRunner` for a missing-policy startup test. [HTTP tests](../../services/query-service/src/test/java/com/simplematch/queryservice/security/QueryHttpSecurityIntegrationTest.java), [startup test](../../services/query-service/src/test/java/com/simplematch/queryservice/security/QuerySecurityStartupTest.java)

This existing support supplies application contexts, assertions, and normal Java test execution.
It supports replacing necessary shell checks of application behavior. [Spring testing support](https://docs.spring.io/spring-boot/reference/testing/index.html)
Testcontainers separately manages the dependency containers in the existing Redis and PostgreSQL test tasks. [Query tasks](../../services/query-service/build.gradle.kts)

### ServiceConnection assessment

The owner requested an assessment against maintainability, readability, and modularity.
`@ServiceConnection` supplies connection details that Spring Boot auto-configuration can consume.
It requires the separate `spring-boot-testcontainers` module. [Spring container integration](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections)

The current fixtures do not repeat container properties across Spring test classes.
The shared PostgreSQL fixture owns its container, connection details, ownership validation, and versioned Flyway migrations.
Both plain JDBC tests and Spring HTTP tests use this fixture. [Database fixture](../../services/query-service/src/test/java/com/simplematch/queryservice/store/QueryProjectionTestDatabase.java)

The production datasource adapter reads the canonical SimpleMatch DSN and service pool settings.
It does not consume Boot's `JdbcConnectionDetails`.
The application test verifies that the canonical DSN remains authoritative. [Datasource adapter](../../shared-java/simplematch-config/src/main/java/com/simplematch/config/SimpleMatchDataSourceAutoConfiguration.java), [application test](../../services/query-service/src/test/java/com/simplematch/queryservice/QueryServiceApplicationTest.java)

Redis tests directly exercise the production connection factory against trusted, mismatched, and untrusted TLS endpoints.
They also test ACL restrictions and invalid credentials.
Boot's automatic connection setup would not replace the custom factory under test. [Connection factory](../../services/query-service/src/main/java/com/simplematch/queryservice/config/QueryRedisConnectionConfiguration.java), [Redis tests](../../services/query-service/src/test/java/com/simplematch/queryservice/config/QueryRedisSecurityIntegrationTest.java)

The current decision is to retain these fixtures without `@ServiceConnection`.
Adoption would require additional integration code or a wider connection-configuration change without eliminating repeated setup.
Reconsider it when a necessary test uses Boot auto-configuration and repeated container properties can be deleted.
Pure JSON verdict tests can continue to use JUnit without a Spring application context.

## Comparable distributed-system projects

### Apache Pulsar: Java tests own Docker clusters

Pulsar's integration tests start real Pulsar services in Docker with Testcontainers and check them with TestNG.
Contributors select a scenario through Gradle:

```shell
./gradlew :tests:integration:integrationTest --tests "org.apache.pulsar.tests.integration.<TestClass>"
```

This command runs in the Pulsar repository with Docker running and its build prerequisites available.
The task builds the required test image when its inputs change or the image disappears.
The documentation recommends individual local tests because the complete suite is expensive. [Integration guide](https://github.com/apache/pulsar/blob/master/tests/README.md)

Java helpers define the cluster and call its start and stop operations.
The cluster helper stops its containers and closes its owned network during ordinary teardown.
An explicit debug option can retain containers. [Test lifecycle](https://github.com/apache/pulsar/blob/master/tests/integration/src/test/java/org/apache/pulsar/tests/integration/topologies/PulsarClusterTestBase.java), [cluster lifecycle](https://github.com/apache/pulsar/blob/master/tests/integration/src/test/java/org/apache/pulsar/tests/integration/topologies/PulsarCluster.java)

Messaging scenarios and helpers contain the assertions about publishing and receiving messages.
The Gradle task reports passed, skipped, and failed tests with full exception traces. [Messaging tests](https://github.com/apache/pulsar/blob/master/tests/integration/src/test/java/org/apache/pulsar/tests/integration/messaging/MessagingSmokeTest.java), [message assertions](https://github.com/apache/pulsar/blob/master/tests/integration/src/test/java/org/apache/pulsar/tests/integration/topologies/PulsarTestBase.java), [task configuration](https://github.com/apache/pulsar/blob/master/tests/integration/build.gradle.kts)

The reusable tools handle execution mechanics. Pulsar still maintains cluster topology, scenario setup, domain assertions, and diagnostic behavior.

### Strimzi: Java tests operate a real Kubernetes cluster

Strimzi's system tests use JUnit Jupiter, Fabric8 Kubernetes Client, and Kubetest4j.
They require an available Kubernetes or OpenShift cluster.
The contributor first builds test dependencies:

```shell
mvn clean install -am -pl systemtest -DskipTests
```

The contributor then selects a real-cluster scenario:

```shell
mvn verify -pl systemtest -P all -Dit.test=KafkaST#testJvmAndResources
```

The cluster context and required operator images must suit the test configuration.
The test command does not provision the underlying cluster. [Contributor guide](https://github.com/strimzi/strimzi-kafka-operator/blob/main/development-docs/TESTING.md), [dependencies](https://github.com/strimzi/strimzi-kafka-operator/blob/main/systemtest/pom.xml)

Java setup creates test resources within that cluster.
The shared JUnit lifecycle deletes resources associated with the test or suite, unless debug configuration disables teardown.
Scenario classes contain Kafka-specific actions and checks. [Shared lifecycle](https://github.com/strimzi/strimzi-kafka-operator/blob/main/systemtest/src/test/java/io/strimzi/systemtest/AbstractST.java), [Kafka scenarios](https://github.com/strimzi/strimzi-kafka-operator/blob/main/systemtest/src/test/java/io/strimzi/systemtest/kafka/KafkaST.java)

A JUnit exception handler collects component logs for supported failure paths and propagates the exception.
Its contributor guide explains how to obtain CI log artifacts. [Failure handler](https://github.com/strimzi/strimzi-kafka-operator/blob/main/systemtest/src/main/java/io/strimzi/systemtest/logs/TestExecutionWatcher.java), [log artifacts](https://github.com/strimzi/strimzi-kafka-operator/blob/main/development-docs/TESTING.md#accessing-github-actions-logs)

Strimzi still maintains substantial domain helpers, templates, failure handling, and lifecycle code.
Adopting Java does not eliminate those responsibilities.
This review does not establish whether either project has another historical-result cache elsewhere.

### Apache Spark Kubernetes Operator: declarative Kubernetes scenarios

The project's Java build uses Gradle. Its Kubernetes integration workflow creates a Minikube cluster and loads the operator image.
The workflow installs the operator with Helm, then selects a scenario group with Chainsaw:

```shell
chainsaw test --test-dir ./tests/e2e/<test-group> --parallel 1
```

This command assumes the preceding cluster and operator setup.
The workflow still uses shell and jq for some environment preparation. [Project workflow](https://github.com/apache/spark-kubernetes-operator/blob/main/.github/workflows/build_and_test.yml)

Chainsaw can apply resources and assert their expected state.
It normally deletes resources that it creates through its create and apply operations.
Failure handlers can collect diagnostics before resource deletion.
It can write JSON, XML, or JUnit reports. [Cleanup](https://kyverno.github.io/chainsaw/latest/quick-start/cleanup/), [failure handlers](https://kyverno.github.io/chainsaw/latest/quick-start/try-catch/), [reports](https://kyverno.github.io/chainsaw/latest/configuration/options/report/)

The contributor maintains YAML scenarios and expected Kubernetes state.
The runner cannot infer SimpleMatch's business assertions or restore external resources that arbitrary commands change.
Embedding the existing shell framework in YAML would retain its maintenance cost.

## Three workflows for SimpleMatch

The following trade-offs are engineering assessments, rather than claims about measured SimpleMatch performance.

| Contributor experience | Java tests with disposable containers | Java tests against Kubernetes | Declarative Kubernetes scenarios |
| --- | --- | --- | --- |
| Normal action | Select a business scenario through Gradle. | Select a deployment or recovery scenario through Gradle. | Select a scenario directory through Chainsaw. |
| Environment | Tests create dependency containers. | An available cluster hosts test resources. | An available cluster hosts declared resources. |
| Failure | A named Java assertion and relevant logs. | A named Java assertion, resource snapshots, and component logs. | A failed step or state assertion, with configured diagnostics. |
| Custom work retained | Test data, protocol interactions, business assertions, and exceptional cleanup. | Business assertions plus Kubernetes ownership, recovery, and diagnostics. | YAML expectations, business probes, and restoration of external state. |
| Main benefit | Uses the project's existing Java and container-test model. | Keeps complex recovery behavior in Java with IDE support. | Replaces common apply, wait, assertion, and cleanup mechanics. |
| Main cost | Docker and image preparation. Containers alone do not establish Kubernetes behavior. | Cluster setup and custom lifecycle code. A client does not define safe recovery. | Another executable and test language. Complex business behavior can produce awkward YAML or embedded scripts. |

These approaches can replace execution mechanics. The project must still define the requirement and observe its result.
Adopt another dependency only when it replaces a complete necessary responsibility.

## Recommended direction

Use Java tests with disposable containers for business behavior and service integration by default.
SimpleMatch already has Testcontainers Redis and PostgreSQL tests and a standalone Query evidence evaluator.
Those provide an existing path for focused scenarios and readable failures. [Query build](../../services/query-service/build.gradle.kts), [evidence evaluator](../../tools/query-evidence-verifier/README.md)

Keep a small separate suite for requirements that depend on Kubernetes behavior.
A Pod replacement, persistent-volume constraint, or node-failure scenario needs that execution environment.
Java suits complex interactions and business assertions.
Declarative scenarios suit resource creation and expected Kubernetes state.
Do not copy Strimzi's complete framework or adopt Chainsaw solely to reduce shell statistics.
Keep a short existing deployment command when another abstraction adds no useful behavior.

Category C implements this cohesive reduction:

1. Retire custom reuse of historical PASS results and the fingerprint, lineage, and planner tests that exist only for that reuse.
2. Retain normal Gradle and container-build caches for deterministic build work.
3. Execute selected live scenarios for current results. Preserve current source and image identity checks where the result depends on them.
4. Use normal named test reports and necessary failure logs. Delete diagnostic artifacts that have no current consumer or requirement.
5. Preserve resource ownership and required environment restoration. Delete only resources owned by the test.

The implementation retires the previous reuse policy and its executable implementation together.
An interrupted run can require repeating a scenario after this reduction. That is the cost of retiring custom checkpoint and reuse machinery.
Gradle supports disabling task state tracking for tasks that act on external services. [Gradle incremental-build contract](https://docs.gradle.org/current/userguide/incremental_build.html#sec:disabling_up_to_date_checks)

These options retain domain invariants and resource ownership.
An old PASS does not establish current environment health.
This research did not measure execution-time or source-size savings.
