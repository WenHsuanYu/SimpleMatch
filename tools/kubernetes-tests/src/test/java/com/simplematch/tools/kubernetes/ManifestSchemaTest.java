package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks current deployment inputs and the small set of required rejection paths. */
@Tag("manifest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManifestSchemaTest {
  private final Path root = Path.of(System.getProperty("verificationRepoRoot"));
  private ManifestSchemas schemas;

  @BeforeAll
  void preparePinnedInputs() throws Exception {
    schemas = ManifestSchemas.prepare(root);
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "test", "staging", "production"})
  void validatesSupportedOverlays(String profile) throws Exception {
    schemas.validate(schemas.render(profile));
  }

  @TestFactory
  Stream<DynamicTest> validatesVerificationWorkloads() throws Exception {
    try (var files = Files.list(root.resolve("deploy/k8s/verification"))) {
      final var manifests = files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
      assertFalse(manifests.isEmpty());
      return manifests.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(),
          () -> schemas.validate(Files.readString(path))));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "apiVersion: v1\nkind: Service\nmetadata: {name: invalid}\nspec: {ports: [{port: invalid}]}",
      "apiVersion: example.invalid/v1\nkind: UnknownResource\nmetadata: {name: missing-schema}",
      ""
  })
  void propagatesSchemaFailuresMissingSchemasAndEmptyInput(String manifest) {
    assertThrows(IllegalArgumentException.class, () -> schemas.validate(manifest));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      """
      apiVersion: apps/v1
      kind: Deployment
      metadata: {name: unobserved-workload}
      spec:
        selector: {matchLabels: {app: example}}
        template:
          metadata: {labels: {app: example}}
          spec: {containers: [{name: app, image: example:v1}]}
      """
  })
  void rejectsCoreWorkloadOmissionsThatTheSchemaAccepts(String manifest) {
    final var failure = assertThrows(IllegalArgumentException.class, () -> schemas.validate(manifest));
    assertTrue(failure.getMessage().contains("requires startup and readiness probes"));
  }
}
