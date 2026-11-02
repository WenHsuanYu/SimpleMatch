# Native verification through Gradle

Run `./gradlew nativeTest` from the repository root.
The task configures and builds `dev-debug`, then executes the existing CTest suite.
Install the normal CMake, Ninja, compiler, and vcpkg prerequisites first.
Set `VCPKG_ROOT` to the existing vcpkg installation.
This task does not install host tools or start Docker resources.

CMake and CTest remain the native build and assertion owners.
Gradle calls them directly without a Shell launcher or a Java copy of their tests.
Normal dependency and compilation caches remain available.
CTest executes on every invocation and rejects empty, failed, skipped, or disabled results.

| Property | Meaning |
| --- | --- |
| `nativePreset` | `dev-debug` by default. Also accepts `full-native-dev`, `ci-fast`, and `ci-sanitize`. |
| `nativeParallelism` | Build and test parallelism from 1 through 8. The default is 2. |
| `nativeEvidence` | New evidence directory. The default is `out/verification/native/<UUID>`. Existing paths fail before configuration. |

For the messaging-enabled native suite, use:

```bash
./gradlew nativeTest -PnativePreset=full-native-dev -PnativeParallelism=2
```

Each run writes `ctest.xml` with named test results in its new evidence directory.
Configure, build, and test commands have separate bounded process deadlines.
Their failures propagate through Gradle.
An interrupted invocation provides no PASS and requires a new evidence path.

The existing CTest capacity benchmark checks actual native command/event integrity and deterministic replay.
The runtime-driver suite also includes bounded 10,000-command local-day replay.
S5 retires the separate ten-cycle Shell loop and its aggregate schema.
See the [disposition](../plans/shell-test-reduction-s5.md) for the intentional verification loss.
Native results do not prove Kafka delivery, Kubernetes recovery, a wall-clock soak, or production capacity.
