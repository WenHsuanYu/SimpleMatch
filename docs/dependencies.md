# Dependencies

## Java and Gradle

This repo's Java dependencies use an explicit ownership model.

- [gradle/libs.versions.toml](/home/alexyu/SimpleMatch/gradle/libs.versions.toml) owns plugin
  versions, BOM coordinates, and non-BOM dependency versions. Libraries managed by Spring Boot or
  Spring Cloud use versionless catalog aliases; their version is resolved only through the applied
  BOM.
- `simplematch.java-conventions` supplies the Java 25 toolchain, JUnit platform, Mockito agent,
  blocking Error Prone checks, and dependency locking to every Java module. Error Prone ignores generated
  sources and is disabled for any
  `compileGeneratedJava` task.
- `simplematch.java-quality` adds blocking Checkstyle, PMD 7.24.0, and SpotBugs where the module has
  production Java sources that require those checks.
- `simplematch.spring-service` owns common Spring Boot and Spring Cloud native Gradle BOM platforms,
  plus narrow Lombok wiring. JDBC, Kafka, gRPC, QuickFIX/J, and Flyway remain in the service build
  that uses them.
- `simplematch.protobuf-contracts` exports the Spring Boot BOM with its protobuf/gRPC API
  dependencies, so generated contract consumers resolve the same BOM-managed library versions
  without catalog duplication.
- `simplematch.protobuf-contracts` owns the shared protobuf source set and gRPC Java generation
  configuration.
- Protobuf contracts opt into the PMD design gate through `simplematch.java-quality`; their
  historical conventions-only Checkstyle/SpotBugs lifecycle remains disabled to avoid treating
  generated contract support code as service quality scope.
- `simplematch.flyway-service` owns service identity, derived migration locations, owner schema
  wiring, and stable root Flyway task aliases.
- Shared modules under `shared-java/*` are not opted into Lombok by default.
- Prefer Java `record` for simple immutable carriers before Lombok.
- Use Lombok sparingly in `services/*` to remove Spring boilerplate such as required-args
  constructors or logging.
- Do not treat Lombok as a blanket style: avoid broad annotations such as `@Data`, and keep domain,
  configuration, mutable, validation-heavy, normalization-heavy, custom-equality, or defensive-copy
  types handwritten.
- Gradle dependency locks are checked in as each Java module's `gradle.lockfile`, plus the root and
  included-build
  `settings-gradle.lockfile` catalog locks. Update them intentionally with:

  ```bash
  ./gradlew -q \
    :shared-java:simplematch-config:dependencies \
    :shared-java:simplematch-contracts:dependencies \
    :services:account-service:dependencies \
    :services:persistence:dependencies \
    :services:quickfix-gateway:dependencies \
    :services:query-service:dependencies \
    :services:risk-service:dependencies \
    --write-locks
  ./gradlew -q -p build-logic dependencies --write-locks \
    --update-locks 'org.jetbrains.kotlin:*'
  ```

  When upgrading the Gradle wrapper, update Gradle's embedded Kotlin resolution with
  `--update-locks` rather than editing the included-build lockfile by hand. Review every lockfile
  diff before committing.
- For local validation, prefer `./gradlew -q <task>` to keep routine Gradle lifecycle output out of
  the console. `-q`
  does not hide failures, compiler diagnostics, test failures, or tool warnings. CI retains
  `--stacktrace` for failure diagnosis.
- After changing dependency wiring, validate with a focused module compile or test before running
  broader static analysis.
- `services/query-service` owns the CQRS read-side runtime dependencies: the shared market-reference
  contract, Spring JDBC, Kafka, Redis, Web, and the service-owned Flyway plugin. Its PostgreSQL
  datasource is supplied by the shared `simplematch-config` auto-configuration, not by a second
  datasource implementation.
- Flyway and H2 runtime/test dependencies are versionless catalog aliases and resolve exclusively
  through the Spring Boot BOM. `protoc` remains explicitly versioned because the protobuf BOM does
  not manage the compiler artifact. The Flyway Gradle plugin remains an explicit build-tool
  artifact, aligned with Spring Boot 4.1's Flyway line, because the BOM has no constraint for that
  plugin artifact.
- H2 2.4 has a cross-session bug for `CHECK (... IN (...))` constraints. Flyway-managed enum checks
  use versioned compatibility migrations with `CASE` expressions instead, while preserving the same
  PostgreSQL constraint semantics.

## Native Dependencies

This repo is primarily a Gradle/Java workspace today.

- The active FIX runtime dependency is **QuickFix/J** in `services/quickfix-gateway`.
- Spring Boot services carry Actuator and the deployed services carry the web runtime required for
  Kubernetes management probes. Query uses port 8086; the other Java workloads expose Actuator on
  port 8080. QuickFIX retains its application-specific `/healthz` and `/readyz` endpoints.
- The root CMake project now builds the policy-aware `matching-engine` ingress seam; the order book,
  matching algorithm, and execution publisher remain future capabilities.
- Native dependencies are installed through **vcpkg** using the manifest at `vcpkg.json`.
- The manifest keeps Protobuf as the native core dependency and groups optional capabilities into
  `tests`, `rpc`, `messaging`, `postgres`, `redis`, `json-config`, and `observability` features.
- CMake consumes vcpkg-provided package configuration directly: Protobuf uses
  `find_package(protobuf CONFIG REQUIRED)`, `protobuf::libprotobuf`, and `protobuf_generate()`, while
  GoogleTest uses `find_package(GTest CONFIG REQUIRED)` and the `GTest::` imported targets. Avoid
  mixing vcpkg Config mode with CMake's legacy `FindProtobuf` Module mode or manually rediscovering
  headers and archives that the package targets already describe.
- CMake presets select only the capability features required by each configuration policy. The
  `ci-fast` preset enables only the current native test closure, while `full-native-dev` enables the
  complete planned native dependency set.
- `ci-fast` and `ci-sanitize` use the repository-owned `x64-linux-ci` overlay triplet. SimpleMatch
  remains a Debug build for CI correctness checks, while vcpkg builds third-party target and host
  dependencies as Release-only packages to reduce cold-cache work. Local `dev-debug` and
  `full-native-dev` continue to use vcpkg's standard `x64-linux` triplet semantics.
- Each preset uses its own build tree and default manifest-mode `vcpkg_installed` directory. Binary
  packages may still be shared through the external vcpkg binary cache, so installation isolation
  does not require recompiling an unchanged package ABI.
- GitHub Actions includes `vcpkg.json`, `CMakePresets.json`, and repository triplet files in the
  binary-cache key. A triplet policy change therefore creates a new outer cache generation; vcpkg's
  own package ABI checking remains the finer-grained validation inside that restored cache.

## Prerequisites

- CMake >= 3.28
- Ninja
- GCC or Clang with C++20 support
- vcpkg ([microsoft/vcpkg](https://github.com/microsoft/vcpkg))

On Linux you may also need system packages required by the selected vcpkg ports or toolchain, such
as `pkg-config`.

## Configure & build

For normal native development, use the `dev-debug` policy:

```bash
cmake --preset dev-debug
cmake --build --preset dev-debug --parallel
ctest --preset dev-debug
```

The main configuration policies are:

- `dev-debug`: current active native development dependencies plus tests, using the standard vcpkg
  Linux triplet.
- `ci-fast`: minimal current dependency closure used by pull-request CI; SimpleMatch is Debug while
  vcpkg dependencies use the release-only `x64-linux-ci` triplet.
- `ci-sanitize`: the `ci-fast` policy with ASan/UBSan enabled and the same release-only dependency
  triplet.
- `full-native-dev`: all currently declared native capability features for broader future
  development work, using the standard vcpkg Linux triplet.

All presets inherit the common Ninja and vcpkg toolchain configuration from the hidden
`vcpkg-base` preset. `VCPKG_ROOT` must point to the vcpkg checkout before configuring locally.

## Notes

- `nlohmann-json` is retained under the optional `json-config` feature for planned native JSON
  configuration support; the active native ingress target does not currently require that feature.
- The native ingress target uses the shared Protobuf sources from `proto/` and GoogleTest fixtures;
  it must not introduce a second wire contract.
- Native tests are controlled by `BUILD_TESTING`; presets that enable tests also select the `tests`
  manifest feature so GoogleTest is present when the test targets are configured.
- Protobuf transitive dependencies such as Abseil and utf8-range are carried by the vcpkg-provided
  `protobuf::libprotobuf` target instead of a repository-owned pkg-config fallback.
