package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KindApiEgressTest {
  @Test
  void bindsOnlyTheConcretePrivateControlPlaneWithoutMutatingTheCanonicalPolicy()
      throws Exception {
    final var root = Path.of(System.getProperty("verificationRepoRoot"));
    final var original = new ObjectMapper(new YAMLFactory()).readTree(root.resolve(
        "deploy/k8s/overlays/local/kubernetes-api-network-policy.yaml").toFile());
    final var updated = KindApiEgress.bind(original, "172.19.0.4");

    assertEquals("172.18.0.0/16", original.at("/spec/egress/1/to/0/ipBlock/cidr").asText());
    assertEquals("172.19.0.4/32", updated.at("/spec/egress/1/to/0/ipBlock/cidr").asText());
    assertEquals(original.at("/spec/egress/0"), updated.at("/spec/egress/0"));
    assertEquals(6443, updated.at("/spec/egress/1/ports/0/port").asInt());
    assertThrows(IllegalArgumentException.class,
        () -> KindApiEgress.bind(original, "8.8.8.8"));
    assertThrows(IllegalArgumentException.class,
        () -> KindApiEgress.bind(original, "172.19.0.999"));
  }
}
