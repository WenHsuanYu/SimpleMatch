package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.simplematch.tools.riskmatchinge2e.KafkaObservationSession.CloseBarrierExpectation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

/** Reuses the existing Kafka observer and exact-byte redelivery implementation inside kind. */
final class KubernetesMatchingEvents {
  private static final String POD = "critical-consumer-kafka-observer";
  private final KubernetesScenarioTarget target;
  private final ObjectMapper json = new ObjectMapper();

  KubernetesMatchingEvents(KubernetesBusinessEnvironment.PublicOrder context) throws Exception {
    target = context.target();
    final var manifest = new ObjectMapper(new YAMLFactory()).readTree(context.settings().root()
        .resolve("deploy/k8s/verification/critical-consumer-kafka-observer-pod.yaml").toFile());
    target.create(manifest);
    target.client().waitFor("condition=Ready", "pod/" + POD);
  }

  JsonNode observe(JsonNode command, JsonNode delivery, String phase) throws Exception {
    assertEquals(command.required("routingPartition").asInt(),
        delivery.required("sourcePartition").asInt());
    final var observed = observeAccepted(command, delivery.required("sourceOffset").asLong(), phase);
    assertEquals(delivery.required("eventId"), observed.required("eventId"));
    assertEquals(delivery.required("sourceOffset").asLong(), observed.required("offset").asLong());
    return observed;
  }

  JsonNode redeliver(JsonNode command, JsonNode delivery) throws Exception {
    invoke("MatchingEventRedeliveryMain", command, delivery.required("sourceOffset").asLong(),
        "redelivery");
    final var observed = result("redelivery", "redelivery.json");
    assertEquals(delivery.required("eventId"), observed.required("eventId"));
    return observed;
  }

  JsonNode observeAccepted(JsonNode command, long offset, String phase) throws Exception {
    invoke("MatchingEventObservationMain", command, offset, phase);
    return result(phase, "matching-event-observation.json");
  }

  JsonNode positions() throws Exception {
    return request("log-end-positions", "");
  }

  JsonNode closeBarriers(CloseBarrierExpectation expectation) throws Exception {
    return request("close-barriers", json.writeValueAsString(expectation));
  }

  private JsonNode request(String operation, String body) throws Exception {
    final var log = target.run().evidence().resolve("kafka-" + operation + "-forward.log");
    final JsonNode snapshot;
    try (var forward = target.client().forward("pod/" + POD, 8081, log);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      final var request = HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + forward.port() + "/" + operation))
          .timeout(Duration.ofSeconds("close-barriers".equals(operation) ? 30 : 5))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new java.io.IOException("Kafka " + operation + " observer returned HTTP " + response.statusCode());
      }
      snapshot = json.readTree(response.body());
    }
    Files.delete(log); // Keep failed transport diagnostics; successful polling needs no separate log.
    return snapshot;
  }

  private void invoke(String main, JsonNode command, long offset, String phase)
      throws Exception {
    final var arguments = List.of("exec", POD, "-c", "observer", "--", "java", "-cp",
        "/app/lib/*", "com.simplematch.tools.riskmatchinge2e." + main,
        "--bootstrap", "kafka:9092", "--topic", "matching.events",
        "--partition", command.required("routingPartition").asText(),
        "--start-offset", Long.toString(offset),
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
