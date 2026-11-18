package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  @ValueSource(strings = {"local", "staging", "production"})
  void validatesSupportedOverlays(String profile) throws Exception {
    schemas.validate(schemas.render(profile));
  }

  @Test
  void rejectsRetiredTestDeploymentOverlay() {
    assertThrows(IllegalArgumentException.class, () -> schemas.render("test"));
  }

  @ParameterizedTest
  @CsvSource({
      "local, 1024, 524288, 524288, 500000, false",
      "staging, 4096, 2097152, 1500000, 4000000, true",
      "production, 4096, 2097152, 1500000, 4000000, true"
  })
  void rendersMatchingCapacityAndPodQosContract(String profile, int restingOrders,
      int outputCapacity, int pendingPublications, int distinctCommands, boolean guaranteed)
      throws Exception {
    final JsonNode pod = matchingPod(schemas.render(profile));
    final JsonNode matching = container(pod, "matching");
    final JsonNode status = container(pod, "runtime-status");

    assertEquals(Integer.toString(restingOrders), environment(matching,
        "MATCHING_MAX_RESTING_ORDERS_PER_INSTRUMENT"));
    assertEquals(Integer.toString(outputCapacity), environment(matching,
        "MATCHING_OUTPUT_CAPACITY"));
    assertEquals(Integer.toString(pendingPublications), environment(matching,
        "MATCHING_MAX_PENDING_PUBLICATIONS"));
    assertEquals(Integer.toString(distinctCommands), environment(matching,
        "MATCHING_MAX_DISTINCT_COMMANDS"));

    // Native Matching reserves two sides per book plus one event and one ring marker.
    final long maximumOutputEvents = 150L * restingOrders * 2 + 1;
    assertTrue(outputCapacity >= maximumOutputEvents + 1,
        "output ring must cover the 150-book worst-case burst and its marker");
    assertTrue(pendingPublications >= maximumOutputEvents,
        "pending publications must cover the 150-book worst-case burst");
    assertEquals(1, Integer.bitCount(outputCapacity), "output ring capacity must be a power of two");
    // Local fills once; promotion templates reserve one fill, one cancel, and one refill.
    final long minimumDistinctCommands = "local".equals(profile) ? maximumOutputEvents
        : 3 * (maximumOutputEvents - 1) + 1;
    assertTrue(distinctCommands >= minimumDistinctCommands,
        "distinct command capacity must cover the profile's command envelope");
    assertMatchingPodQos(pod, profile, guaranteed);
    if (guaranteed) {
      assertEquals("3", matching.path("resources").path("requests").path("cpu").asText());
      assertEquals("3", matching.path("resources").path("limits").path("cpu").asText());
      assertEquals("true", pod.path("nodeSelector")
          .path("simplematch.io/cpu-manager-static").asText());
      assertEquals("50m", status.path("resources").path("requests").path("cpu").asText());
      assertEquals("32Mi", status.path("resources").path("requests").path("memory").asText());
    } else {
      assertFalse(pod.path("nodeSelector").has("simplematch.io/cpu-manager-static"));
    }
  }

  @ParameterizedTest
  @CsvSource({"local, false", "staging, true", "production, true"})
  void matchingArtifactOciFallbackPreservesPodQos(String profile, boolean guaranteed)
      throws Exception {
    final JsonNode ordinaryPod = matchingPod(schemas.render(profile));
    assertTrue(ordinaryPod.path("initContainers").isMissingNode());
    final String renderedFallback = schemas.renderMatchingArtifactOciFallback(profile);
    schemas.validate(renderedFallback);
    final JsonNode pod = matchingPod(renderedFallback);
    assertEquals("market-reference", pod.path("volumes").get(0).path("name").asText());
    assertTrue(pod.path("volumes").get(0).has("emptyDir"));
    assertEquals(1, pod.path("initContainers").size());
    final JsonNode dataImage = pod.path("initContainers").get(0);
    assertEquals("artifact-data-image", dataImage.path("name").asText());
    assertEquals("market-reference", dataImage.path("volumeMounts").get(0)
        .path("name").asText());
    assertEquals("/work", dataImage.path("volumeMounts").get(0).path("mountPath").asText());
    assertMatchingPodQos(pod, profile + " OCI fallback", guaranteed);
  }

  private static void assertMatchingPodQos(JsonNode pod, String profile, boolean guaranteed) {
    boolean allContainersGuaranteed = true;
    boolean anyResourceConfigured = false;
    int containerCount = 0;
    for (String group : List.of("containers", "initContainers")) {
      for (JsonNode candidate : pod.path(group)) {
        containerCount++;
        final JsonNode requests = candidate.path("resources").path("requests");
        final JsonNode limits = candidate.path("resources").path("limits");
        anyResourceConfigured |= requests.size() > 0 || limits.size() > 0;
        final boolean cpuMemorySet = requests.hasNonNull("cpu") && requests.hasNonNull("memory")
            && limits.hasNonNull("cpu") && limits.hasNonNull("memory");
        allContainersGuaranteed &= cpuMemorySet && requests.path("cpu").equals(limits.path("cpu"))
            && requests.path("memory").equals(limits.path("memory"));
      }
    }
    assertTrue(containerCount >= 2, "Matching Pod must include the main and status containers");
    assertTrue(anyResourceConfigured, "Matching Pod must not have BestEffort QoS");
    assertEquals(guaranteed, allContainersGuaranteed, profile + " Matching Pod QoS contract");
  }

  private static JsonNode matchingPod(String manifest) throws Exception {
    final var mapper = new ObjectMapper(new YAMLFactory());
    try (var parser = mapper.getFactory().createParser(manifest)) {
      while (parser.nextToken() != null) {
        final JsonNode resource = mapper.readTree(parser);
        if (resource != null && "StatefulSet".equals(resource.path("kind").asText())
            && "matching".equals(resource.path("metadata").path("name").asText())) {
          return resource.path("spec").path("template").path("spec");
        }
      }
    }
    throw new IllegalArgumentException("rendered manifest lacks StatefulSet/matching");
  }

  private static JsonNode container(JsonNode pod, String name) {
    return StreamSupport.stream(pod.path("containers").spliterator(), false)
        .filter(candidate -> name.equals(candidate.path("name").asText()))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("missing container: " + name));
  }

  private static String environment(JsonNode container, String name) {
    return StreamSupport.stream(container.path("env").spliterator(), false)
        .filter(candidate -> name.equals(candidate.path("name").asText()))
        .map(candidate -> candidate.path("value").asText())
        .findFirst().orElseThrow(() -> new IllegalArgumentException("missing env: " + name));
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
