package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Keeps false readiness and image drift from passing the public Recreate owner check. */
final class KubernetesMarketDataStreamerTest {
  private static final String IMAGE = "simplematch/marketdata-streamer:verified";
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requiresReadyPodAndLockedImage() throws Exception {
    final var valid = json.readTree("""
        {"metadata":{"uid":"new-owner"},"spec":{"nodeName":"worker-1",
          "containers":[{"name":"marketdata-streamer",
            "image":"simplematch/marketdata-streamer:verified"}]},
         "status":{"phase":"Running",
           "conditions":[{"type":"Ready","status":"True"}],
           "containerStatuses":[{"name":"marketdata-streamer","ready":true}]}}
        """);
    assertTrue(KubernetesMarketDataStreamer.ready(valid, IMAGE));

    final var wrongImage = valid.deepCopy();
    ((ObjectNode) wrongImage.path("spec").path("containers").get(0))
        .put("image", "simplematch/marketdata-streamer:stale");
    assertFalse(KubernetesMarketDataStreamer.ready(wrongImage, IMAGE));

    final var notReady = valid.deepCopy();
    ((ObjectNode) notReady.path("status").path("containerStatuses").get(0)).put("ready", false);
    assertFalse(KubernetesMarketDataStreamer.ready(notReady, IMAGE));

    final var removing = valid.deepCopy();
    ((ObjectNode) removing.path("metadata")).put("deletionTimestamp", "2026-11-04T00:00:00Z");
    assertFalse(KubernetesMarketDataStreamer.ready(removing, IMAGE));
  }
}
