# Dependencies

## Java and Gradle

This repo's Java dependencies are managed through the root Gradle build plus each module's own `build.gradle.kts` file.

- The root [build.gradle.kts](/home/alexyu/SimpleMatch/build.gradle.kts) centralizes shared Java build conventions across subprojects.
- Lombok is applied centrally to every Gradle project under `services/*` via `compileOnly`, `annotationProcessor`, `testCompileOnly`, and `testAnnotationProcessor`.
- Shared modules under `shared-java/*` are not opted into Lombok by default.
- Prefer Java `record` for simple immutable carriers before Lombok.
- Use Lombok sparingly in `services/*` to remove Spring boilerplate such as required-args constructors or logging.
- Do not treat Lombok as a blanket style: avoid broad annotations such as `@Data`, and keep domain, configuration, mutable, validation-heavy, normalization-heavy, custom-equality, or defensive-copy types handwritten.
- After changing root dependency wiring, validate with a focused module compile before running broader static analysis.

## Native Dependencies

This repo is primarily a Gradle/Java workspace today.

- The active FIX runtime dependency is **QuickFix/J** in `services/quickfix-gateway`.
- `services/quickfix-gateway` now also carries Spring Boot web + actuator runtime dependencies so Kubernetes probes can terminate on `/healthz` and `/readyz`.
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
- When a real native `matching-engine` target lands in the repo, revisit this document together with the root CMake/vcpkg setup.
