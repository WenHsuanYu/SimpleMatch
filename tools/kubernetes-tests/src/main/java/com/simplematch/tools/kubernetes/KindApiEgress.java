package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Restricts local kind API access to the concrete run-owned control-plane address. */
final class KindApiEgress {
  private static final Pattern IPV4 = Pattern.compile(
      "([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})");

  private KindApiEgress() {}

  static String controlPlaneIp(KubernetesClient client, String cluster) throws Exception {
    final String name = cluster + "-control-plane";
    final var node = client.read("get", "node", name, "-o", "json");
    if (!name.equals(node.path("metadata").path("name").asText())) {
      throw new IllegalStateException("kind control-plane Kubernetes identity changed");
    }
    final var addresses = java.util.stream.StreamSupport.stream(
        node.path("status").path("addresses").spliterator(), false)
        .filter(address -> "InternalIP".equals(address.path("type").asText()))
        .map(address -> address.path("address").asText()).toList();
    if (addresses.size() != 1) {
      throw new IllegalStateException("kind control-plane has no unique internal address");
    }
    requirePrivateIpv4(addresses.get(0));
    return addresses.get(0);
  }

  static ObjectNode bind(JsonNode original, String controlPlaneIp) {
    requirePrivateIpv4(controlPlaneIp);
    if (!"NetworkPolicy".equals(original.path("kind").asText())
        || !"simplematch-java-services-kubernetes-api".equals(
            original.path("metadata").path("name").asText())) {
      throw new IllegalArgumentException("unexpected kind API egress policy");
    }
    final var rules = original.path("spec").path("egress");
    if (rules.size() != 2) {
      throw new IllegalStateException("canonical kind API egress policy changed");
    }
    requireRule(rules.get(0), "10.96.0.0/12", 443);
    requireRule(rules.get(1), "172.18.0.0/16", 6443);
    final ObjectNode updated = original.deepCopy();
    ((ObjectNode) updated.path("spec").path("egress").path(1).path("to").path(0)
        .path("ipBlock")).put("cidr", controlPlaneIp + "/32");
    return updated;
  }

  private static void requireRule(JsonNode rule, String cidr, int port) {
    if (rule.path("to").size() != 1
        || !cidr.equals(rule.path("to").path(0).path("ipBlock").path("cidr").asText())
        || rule.path("ports").size() != 1
        || rule.path("ports").path(0).path("port").asInt() != port
        || !"TCP".equals(rule.path("ports").path(0).path("protocol").asText())) {
      throw new IllegalStateException("canonical kind API egress policy changed");
    }
  }

  private static void requirePrivateIpv4(String value) {
    final var matcher = IPV4.matcher(value == null ? "" : value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("kind control-plane address must be private IPv4");
    }
    final int[] octets = IntStream.rangeClosed(1, 4)
        .map(index -> Integer.parseInt(matcher.group(index))).toArray();
    if (Arrays.stream(octets).anyMatch(part -> part > 255)) {
      throw new IllegalArgumentException("kind control-plane address is not IPv4");
    }
    if (!privateRange(octets)) {
      throw new IllegalArgumentException("kind control-plane address is not private IPv4");
    }
  }

  private static boolean privateRange(int[] octets) {
    return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
        || (octets[0] == 192 && octets[1] == 168);
  }
}
