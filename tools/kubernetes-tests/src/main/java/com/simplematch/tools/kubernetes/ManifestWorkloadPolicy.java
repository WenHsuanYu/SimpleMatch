package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Requires basic controller health observation that Kubernetes schemas leave optional. */
final class ManifestWorkloadPolicy {
  private ManifestWorkloadPolicy() {}

  static void validate(String rendered) throws Exception {
    final var yaml = new ObjectMapper(new YAMLFactory());
    try (var parser = yaml.getFactory().createParser(rendered)) {
      while (parser.nextToken() != null) {
        final JsonNode resource = yaml.readTree(parser);
        if (resource == null || resource.isNull()) {
          continue;
        }
        final String kind = resource.path("kind").asText();
        final String name = resource.path("metadata").path("name").asText();
        final var containers = resource.at("/spec/template/spec/containers");
        if (Set.of("Deployment", "StatefulSet").contains(kind)
            && !StreamSupport.stream(containers.spliterator(), false)
                .anyMatch(container -> container.has("startupProbe")
                    && container.has("readinessProbe"))) {
          throw new IllegalArgumentException(
              kind + "/" + name + " requires startup and readiness probes");
        }
      }
    }
  }
}
