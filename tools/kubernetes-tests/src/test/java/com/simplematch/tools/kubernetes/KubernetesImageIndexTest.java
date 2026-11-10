package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that imported OCI indexes resolve one platform and one immutable image config. */
class KubernetesImageIndexTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INDEX_TYPE = "application/vnd.oci.image.index.v1+json";
  private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String CONFIG_TYPE = "application/vnd.oci.image.config.v1+json";
  private static final String ROOT = digest('0');
  private static final String NESTED = digest('1');
  private static final String IMAGE = digest('2');
  private static final String OTHER_IMAGE = digest('3');
  private static final String CONFIG = digest('4');
  private static final String OTHER_CONFIG = digest('5');

  @Test
  void acceptsAPlatformLessWrapperAndOneLinuxAmd64ImageManifest() throws Exception {
    final var content = Map.of(
        ROOT, index(List.of(indexDescriptor(NESTED))),
        NESTED, index(List.of(descriptor(IMAGE, "linux", "amd64"))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void acceptsAnUnknownPlatformWrapperWhenItsChildHasVerifiedAmd64Config() throws Exception {
    final var content = Map.of(
        ROOT, index(List.of(indexDescriptor(NESTED, "unknown", "unknown"))),
        NESTED, index(List.of(descriptor(IMAGE, "linux", "amd64"))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void acceptsOneDirectLinuxAmd64Manifest() throws Exception {
    final var content = Map.of(ROOT, manifest(CONFIG), CONFIG, config("linux", "amd64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void skipsAnExplicitlyMarkedPlatformLessAttestation() throws Exception {
    final var content = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"), Map.of(
            "digest", OTHER_IMAGE,
            "mediaType", MANIFEST_TYPE,
            "annotations", Map.of("vnd.docker.reference.type", "attestation-manifest")))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void rejectsUnverifiedUnlabelledContentEvenWhenAnotherImageIsValid() {
    final var content = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"), descriptor(OTHER_IMAGE))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void ignoresAttestationsAndOtherArchitecturesWithoutSelectingTheirConfig() throws Exception {
    final var content = Map.of(
        ROOT, index(List.of(descriptor(IMAGE), descriptor(OTHER_IMAGE, "linux", "arm64"))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"),
        OTHER_IMAGE, manifest(OTHER_CONFIG),
        OTHER_CONFIG, config("linux", "arm64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void treatsRepeatedDescriptorsForOneImmutableManifestAsOneCandidate() throws Exception {
    final var content = Map.of(
        ROOT, index(List.of(descriptor(IMAGE), descriptor(IMAGE, "linux", "amd64"))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertEquals(CONFIG, KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void rejectsAmbiguousOrWrongPlatformImages() {
    final var ambiguous = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"),
            descriptor(OTHER_IMAGE, "linux", "amd64"))),
        IMAGE, manifest(CONFIG),
        OTHER_IMAGE, manifest(OTHER_CONFIG),
        CONFIG, config("linux", "amd64"),
        OTHER_CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, ambiguous::get));

    final var wrongPlatform = Map.of(
        ROOT, index(List.of(descriptor(IMAGE))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "arm64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, wrongPlatform::get));

    final var ambiguousSameConfig = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"),
            descriptor(OTHER_IMAGE, "linux", "amd64"))),
        IMAGE, manifest(CONFIG),
        OTHER_IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, ambiguousSameConfig::get));
  }

  @Test
  void rejectsInvalidMediaTypesEvenWhenAnotherManifestIsValid() {
    final var invalid = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"), Map.of(
            "digest", OTHER_IMAGE, "mediaType", "application/x-unverified"))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, invalid::get));

    final var missing = Map.of(
        ROOT, index(List.of(descriptor(IMAGE, "linux", "amd64"),
            Map.<String, Object>of("digest", OTHER_IMAGE))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, missing::get));
  }

  @Test
  void validatesEachDuplicateDescriptorAgainstItsOwnPlatformClaim() {
    final var content = Map.of(
        ROOT, index(List.of(descriptor(OTHER_IMAGE), descriptor(OTHER_IMAGE, "linux", "amd64"),
            descriptor(IMAGE, "linux", "amd64"))),
        OTHER_IMAGE, manifest(OTHER_CONFIG),
        OTHER_CONFIG, config("linux", "arm64"),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void rejectsADeclaredAmd64NestedBranchThatContainsOnlyArm64() {
    final var content = Map.of(
        ROOT, index(List.of(indexDescriptor(NESTED, "linux", "amd64"),
            descriptor(IMAGE, "linux", "amd64"))),
        NESTED, index(List.of(descriptor(OTHER_IMAGE, "linux", "arm64"))),
        OTHER_IMAGE, manifest(OTHER_CONFIG),
        OTHER_CONFIG, config("linux", "arm64"),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, content::get));
  }

  @Test
  void rejectsMalformedOrCyclicIndexes() {
    final var empty = Map.of(ROOT, index(List.of()));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, empty::get));
    final var cyclic = Map.of(
        ROOT, index(List.of(indexDescriptor(NESTED), descriptor(IMAGE, "linux", "amd64"))),
        NESTED, index(List.of(indexDescriptor(ROOT))),
        IMAGE, manifest(CONFIG),
        CONFIG, config("linux", "amd64"));
    assertThrows(IllegalStateException.class,
        () -> KubernetesImageIndex.resolveConfig(ROOT, cyclic::get));
  }

  private static JsonNode index(List<Map<String, Object>> manifests) {
    return JSON.valueToTree(Map.of(
        "schemaVersion", 2, "mediaType", INDEX_TYPE, "manifests", manifests));
  }

  private static Map<String, Object> descriptor(String digest) {
    return Map.of("digest", digest, "mediaType", MANIFEST_TYPE);
  }

  private static Map<String, Object> indexDescriptor(String digest) {
    return Map.of("digest", digest, "mediaType", INDEX_TYPE);
  }

  private static Map<String, Object> indexDescriptor(String digest, String os, String architecture) {
    return Map.of("digest", digest, "mediaType", INDEX_TYPE,
        "platform", Map.of("os", os, "architecture", architecture));
  }

  private static Map<String, Object> descriptor(String digest, String os, String architecture) {
    return Map.of("digest", digest, "mediaType", MANIFEST_TYPE,
        "platform", Map.of("os", os, "architecture", architecture));
  }

  private static JsonNode manifest(String configDigest) {
    return JSON.valueToTree(Map.of("schemaVersion", 2, "mediaType", MANIFEST_TYPE,
        "config", Map.of("digest", configDigest, "mediaType", CONFIG_TYPE)));
  }

  private static JsonNode config(String os, String architecture) {
    return JSON.valueToTree(Map.of("os", os, "architecture", architecture));
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
