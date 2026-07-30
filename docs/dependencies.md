# Dependencies

## Java and Gradle

This repo's Java dependencies use an explicit ownership model.

- [gradle/libs.versions.toml](/home/alexyu/SimpleMatch/gradle/libs.versions.toml) owns plugin versions, BOM coordinates,
  and non-BOM dependency versions. Libraries managed by Spring Boot or Spring Cloud use versionless catalog aliases;
  their version is resolved only through the applied BOM.
- `simplematch.java-conventions` supplies the Java 25 toolchain, JUnit platform, Mockito agent, Error Prone warnings,
  and dependency locking to every Java module. Error Prone ignores generated sources and is disabled for any
  `compileGeneratedJava` task.
- `simplematch.java-quality` adds blocking Checkstyle, PMD 7.24.0, and SpotBugs where the module has production Java
  sources that require those checks.
- `simplematch.spring-service` owns common Spring Boot and Spring Cloud native Gradle BOM platforms, plus narrow
  Lombok wiring. JDBC, Kafka, gRPC, QuickFIX/J, and Flyway remain in the service build that uses them.
- `simplematch.protobuf-contracts` exports the Spring Boot BOM with its protobuf/gRPC API dependencies, so generated
  contract consumers resolve the same BOM-managed library versions without catalog duplication.
- `simplematch.protobuf-contracts` owns the shared protobuf source set and gRPC Java generation configuration.
- Protobuf contracts opt into the PMD design gate through `simplematch.java-quality`; their historical conventions-only
  Checkstyle/SpotBugs lifecycle remains disabled to avoid treating generated contract support code as service quality
  scope.
- `simplematch.flyway-service` owns service identity, derived migration locations, owner schema wiring, and stable root
  Flyway task aliases.
- Shared modules under `shared-java/*` are not opted into Lombok by default.
- Prefer Java `record` for simple immutable carriers before Lombok.
- Use Lombok sparingly in `services/*` to remove Spring boilerplate such as required-args constructors or logging.
- Do not treat Lombok as a blanket style: avoid broad annotations such as `@Data`, and keep domain, configuration,
  mutable, validation-heavy, normalization-heavy, custom-equality, or defensive-copy types handwritten.
- Gradle dependency locks are checked in as each Java module's `gradle.lockfile`, plus the root and included-build
  `settings-gradle.lockfile` catalog locks. Update them intentionally with:

  ```bash
  ./gradlew -q \
    :shared-java:simplematch-config:dependencies \
    :shared-java:simplematch-contracts:dependencies \
    :services:account-service:dependencies \
    :services:persistence:dependencies \
    :services:quickfix-gateway:dependencies \
    :services:risk-service:dependencies \
    --write-locks
  ./gradlew -q -p build-logic dependencies --write-locks
  ```

  Review every lockfile diff before committing.
- For local validation, prefer `./gradlew -q <task>` to keep routine Gradle lifecycle output out of the console. `-q`
  does not hide failures, compiler diagnostics, test failures, or tool warnings. CI retains `--stacktrace` for failure
  diagnosis.
- After changing dependency wiring, validate with a focused module compile or test before running broader static
  analysis.
- Flyway and H2 runtime/test dependencies are versionless catalog aliases and resolve exclusively through the Spring
  Boot BOM. `protoc` remains explicitly versioned because the protobuf BOM does not manage the compiler artifact. The
  Flyway Gradle plugin remains an explicit build-tool artifact, aligned with Spring Boot 4.1's Flyway line, because the
  BOM has no constraint for that plugin artifact.
- H2 2.4 has a cross-session bug for `CHECK (... IN (...))` constraints. Flyway-managed enum checks use versioned
  compatibility migrations with `CASE` expressions instead, while preserving the same PostgreSQL constraint semantics.

## Native Dependencies

This repo is primarily a Gradle/Java workspace today.

- The active FIX runtime dependency is **QuickFix/J** in `services/quickfix-gateway`.
- `services/quickfix-gateway` now also carries Spring Boot web + actuator runtime dependencies so Kubernetes probes can
  terminate on `/healthz` and `/readyz`.
- Native dependency guidance is retained only for future native modules such as `matching-engine`.
- Most native dependencies are expected to be installed via **vcpkg** using the manifest at `vcpkg.json`.

## Prerequisites

- CMake >= 3.21
- GCC or Clang with C++20 support
- vcpkg ([microsoft/vcpkg](https://github.com/microsoft/vcpkg))

On Linux you may also need system packages (varies by distro/toolchain), e.g.:

- `pkg-config`, `ninja-build` (optional but recommended)
- `openssl` dev headers
- `zlib` dev headers

## Configure & build (example)

```bash
# Recommended: use CMake Presets.
# This repo's `vcpkg` preset also sets VCPKG_INSTALLED_DIR to `third_party/vcpkg_installed/`.
cmake --preset vcpkg
cmake --build --preset vcpkg -j

# (Equivalent CLI form)
# assuming VCPKG_ROOT points to your vcpkg clone
# cmake -S . -B build-vcpkg \
#   -DCMAKE_BUILD_TYPE=Release \
#   -DCMAKE_TOOLCHAIN_FILE=$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake \
#   -DVCPKG_INSTALLED_DIR=$PWD/third_party/vcpkg_installed
# cmake --build build-vcpkg -j
```

## Notes

- `nlohmann-json` is used for loading the app JSON config (Task 0).
- If you are building without vcpkg, you must provide `nlohmann_json` to CMake via your environment/toolchain.
- When a real native `matching-engine` target lands in the repo, revisit this document together with the root
  CMake/vcpkg setup.
