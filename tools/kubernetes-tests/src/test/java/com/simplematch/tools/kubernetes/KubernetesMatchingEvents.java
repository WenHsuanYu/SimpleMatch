package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

/** Reuses the existing Kafka observer and exact-byte redelivery implementation inside kind. */
final class KubernetesMatchingEvents {
  private static final String POD = "matching-event-outage-observer";
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesMatchingEvents(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    target = context.target();
    final var manifest = new ObjectMapper(new YAMLFactory()).readTree(context.settings().root()
        .resolve("deploy/k8s/verification/matching-event-observer-pod.yaml").toFile());
    target.create(manifest);
    target.client().waitFor("condition=Ready", "pod/" + POD);
  }

  JsonNode observe(JsonNode command, JsonNode delivery, String phase) throws Exception {
    invoke("MatchingEventObservationMain", command, delivery, phase);
    final var observed = result(phase, "matching-event-observation.json");
    assertEquals(delivery.required("eventId"), observed.required("eventId"));
    assertEquals(delivery.required("sourceOffset").asLong(), observed.required("offset").asLong());
    return observed;
  }

  JsonNode redeliver(JsonNode command, JsonNode delivery) throws Exception {
    invoke("MatchingEventRedeliveryMain", command, delivery, "redelivery");
    final var observed = result("redelivery", "redelivery.json");
    assertEquals(delivery.required("eventId"), observed.required("eventId"));
    return observed;
  }

  private void invoke(String main, JsonNode command, JsonNode delivery, String phase)
      throws Exception {
    assertEquals(command.required("routingPartition").asInt(),
        delivery.required("sourcePartition").asInt());
    final var arguments = List.of("exec", POD, "-c", "observer", "--", "java", "-cp",
        "/app/lib/*", "com.simplematch.tools.riskmatchinge2e." + main,
        "--bootstrap", "kafka:9092", "--topic", "matching.events",
        "--partition", command.required("routingPartition").asText(),
        "--start-offset", delivery.required("sourceOffset").asText(),
        "--command-id", command.required("commandId").asText(),
        "--order-id", command.required("orderId").asText(),
        "--timeout-seconds", "60", "--evidence-dir", "/tmp/" + phase);
    target.client().execute("", Duration.ofSeconds(75), arguments.toArray(String[]::new)).checked();
  }

  private JsonNode result(String phase, String name) throws Exception {
    final var value = target.client().read("exec", POD, "-c", "observer", "--",
        "cat", "/tmp/" + phase + "/" + name);
    Files.writeString(target.run().evidence().resolve("matching-" + phase + ".json"),
        json.writeValueAsString(value));
    return value;
  }
}
