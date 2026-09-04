# Phase 0 Baseline Evidence

This is an execution-state record for the Taiwan event-driven refactor. It is not
target-architecture documentation.

## 0.1 Worktree Review

Reviewed on 2026-07-27 from recoverable commit `a70509c`
(`test: characterize rejected FIX risk submissions`). The worktree was clean:
there were no tracked or untracked changes to classify or checkpoint.

The review treats the following ignored paths as generated or runtime artifacts, not candidate
source changes:

- Gradle outputs: `build/` and `.gradle/`
- Native outputs: `build-vcpkg/` and `build-sanitize/`
- QuickFIX stores, logs, and WAL files under `data/quickfix/`
- Derived code graphs: `.code-review-graph/` and `graphify-out/`

Only tracked source, tests, migrations, configuration, and documentation may be included in a
refactor checkpoint. A clean `git status -sb` is required before each subsequent phase commit.

## 0.2 Module Inventory

| Path or capability                  | Current state                                                                | Classification                     |
|-------------------------------------|------------------------------------------------------------------------------|------------------------------------|
| `shared-java/simplematch-config`    | Gradle Java library for shared configuration loading and UUID utilities      | Implemented module                 |
| `shared-java/simplematch-contracts` | Gradle Java library that generates Protobuf and gRPC contracts from `proto/` | Implemented module                 |
| `services/account-service`          | Spring Boot Gradle service with account owner schema migrations              | Implemented module                 |
| `services/persistence`              | Spring Boot Gradle service with projection owner schema migrations           | Implemented module                 |
| `services/quickfix-gateway`         | Spring Boot QuickFIX/J gateway with gRPC risk submission and WAL support     | Implemented module                 |
| `services/risk-service`             | Spring Boot durable admission service with an outbox owner schema            | Implemented module                 |
| `matching-engine`                   | No native target or source directory is registered by `CMakeLists.txt`       | Target capability, not implemented |
| `marketdata-publisher`              | Historical README-only runtime name; later retired by #119                 | Superseded capability |
| `marketdata-streamer`               | Listed in the README service landscape but absent from Gradle settings       | Target capability, not implemented |
| `query-service`                     | Listed in the README service landscape but absent from Gradle settings       | Target capability, not implemented |

Deployment assets are also partial: `deploy/k8s/` contains QuickFIX gateway StatefulSet and service
scaffolding plus a risk-service outbox connector ConfigMap. It is not a complete Kubernetes
deployment for every intended service.

## 0.4 Compatibility Inventory

Commit 0.3 is recorded by the QuickFIX characterization test. Commit 0.4 adds the checked-in v1
message-field inventory at
`shared-java/simplematch-contracts/src/test/resources/v1-protobuf-field-numbers.properties`.
`V1ProtobufCompatibilityInventoryTest` compares that inventory with generated Protobuf descriptors
and fails on a field addition, removal, rename, or number change.

## 0.5 Validation Evidence

The following checks passed on 2026-07-27:

| Command                                                          | Result                                     |
|------------------------------------------------------------------|--------------------------------------------|
| `./gradlew -q staticAnalysis`                                    | Passed                                     |
| `./gradlew -q test :services:quickfix-gateway:certificationTest` | Passed                                     |
| `bash scripts/test-check-markdown-links.sh`                      | Passed                                     |
| `cmake -S . -B /tmp/simplematch-phase0-cmake`                    | Passed                                     |
| `cmake --build /tmp/simplematch-phase0-cmake`                    | Passed; no native target is registered yet |

The Flyway/PostgreSQL smoke script is an environment-only blocker:
`bash scripts/run-flyway-ci-checks.sh` fails before migration execution because the local PostgreSQL
role cannot create the ephemeral
`simplematch_account_service_ci` database. A role with `CREATEDB`, or the CI PostgreSQL service
configuration, is required to run that check. This is not a schema or application-code failure.

## Phase Gate

- [x] Current behavior is characterized by the existing FIX, risk submission, WAL, outbox, and
  QuickFIX certification tests.
- [x] The module inventory labels README-only services and the matching engine as target
  capabilities rather than implemented deployables.
- [x] The baseline is recoverable through the dedicated Phase 0 checkpoint commits; no production
  runtime behavior changed in this phase.
