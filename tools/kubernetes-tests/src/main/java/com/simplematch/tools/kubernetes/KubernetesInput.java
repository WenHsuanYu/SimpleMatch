package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;

/** Validates the explicitly selected approved delivery before an environment is mutated. */
final class KubernetesInput {
  private final VerifiedMarketReferenceArtifact artifact;
  private final ObjectNode configMap;

  private KubernetesInput(VerifiedMarketReferenceArtifact artifact,
      ObjectNode configMap) {
    this.artifact = artifact;
    this.configMap = configMap.deepCopy();
  }

  static KubernetesInput load(Path deliveryManifest, LocalDate day) throws IOException {
    final Path manifest = deliveryManifest.toRealPath();
    final JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(manifest.toFile());
    final String prefix = "market-reference-" + day.toString().replace("-", "") + "-";
    if (!"v1".equals(document.path("apiVersion").asText())
        || !"ConfigMap".equals(document.path("kind").asText())
        || !document.path("immutable").asBoolean()
        || !document.path("metadata").path("name").asText().startsWith(prefix)) {
      throw new IllegalArgumentException("approved delivery must be immutable and match its day");
    }
    final JsonNode data = document.path("binaryData");
    final byte[] bytes = Base64.getDecoder().decode(data.path("market_reference.json").asText());
    final String checksum = new String(Base64.getDecoder().decode(
        data.path("market_reference.sha256").asText()), StandardCharsets.UTF_8).trim();
    final var verified = new MarketReferenceArtifactStartupValidator(new ObjectMapper())
        .validate(bytes, checksum, day);
    return new KubernetesInput(verified, (ObjectNode) document);
  }

  ObjectNode configMap() {
    return configMap.deepCopy();
  }

  VerifiedMarketReferenceArtifact artifact() {
    return artifact;
  }
}
