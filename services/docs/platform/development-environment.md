# Development Environment

This is the canonical target specification for the SimpleMatch development environment. It describes
the intended toolchain and repository shape, not a record of a particular delivery phase.

## Repository shape

SimpleMatch remains a polyglot monorepo. Java and Spring Cloud services live under `services/` and
share Gradle build logic; the latency-sensitive native matching core uses CMake. Protocol
definitions are kept in `proto/`, FIX dictionaries in `fix-spec/`, and QuickFix session
configuration at
[config/quickfix/acceptor.cfg](../../../config/quickfix/acceptor.cfg). Cross-cutting target
specifications live in
`services/docs/`; service-owned target specifications live beside their owning service.

## Toolchain

- Use the repository Gradle Wrapper for Java builds and tests; do not require a system Gradle
  installation.
- Use the supported JDK and the C++20-capable compiler/toolchain documented by the build
  configuration.
- Use CMake and the repository's vcpkg preset for native work.
- Treat generated protocol or build artifacts as outputs of their owning build, not hand-maintained
  specifications.

## Development boundaries

The development environment must preserve the separation between target design and execution state.
Repository workflow instructions, implementation plans, phase gates, and certification evidence
remain under their existing repository documentation locations rather than moving into this
target-platform area.

Build commands and currently enabled checks are operational repository guidance; consult the
development workflow for those executable details.
