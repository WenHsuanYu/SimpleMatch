package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks only the service-owned prerequisites used by this Pod-deletion scenario. */
final class ConnectPrerequisites {
  void verify(Path directory) throws IOException {
    final EvidenceFiles files = new EvidenceFiles(directory);
    require(integer(files.json("connect-deployment.json").path("spec"), "replicas") == 2,
        "Expected two Connect workers.");
    ConnectOwners.readyPods(files.json("connect-pods-before.json"));
    final JsonNode postgres = files.json("prerequisites/postgres.json");
    require(integer(postgres.path("status"), "readyReplicas") >= 1,
        "PostgreSQL is unavailable before the fault.");
    for (String service : new String[] {"account-service", "risk-service"}) {
      job(files.json("prerequisites/" + service + "-flyway.json"), service + "-flyway");
      connector(files.json(service + "-outbox-configmap.json"), service);
    }
    job(files.json("prerequisites/kafka-topic-provisioning.json"), "kafka-topic-provisioning");
    for (String topic : new String[] {
        "simplematch-connect-configs", "simplematch-connect-offsets",
        "simplematch-connect-status"}) {
      topic(Files.readString(files.file("prerequisites/" + topic + ".txt")), topic);
    }
  }

  private static void job(JsonNode job, String name) {
    require(text(job.path("metadata"), "name").equals(name), "Wrong prerequisite Job.");
    for (JsonNode condition : job.path("status").path("conditions")) {
      if (condition.path("type").asText().equals("Complete")
          && condition.path("status").asText().equals("True")) {
        return;
      }
    }
    throw new IllegalArgumentException("Incomplete prerequisite Job: " + name);
  }

  private static void connector(JsonNode document, String service) throws IOException {
    final String name = service + "-outbox";
    require(text(document.path("metadata"), "name").equals(name + "-connector"),
        "Connector is not service-owned.");
    final JsonNode connector = JsonReports.parse(text(document.path("data"), "connector.json"));
    require(text(connector, "name").equals(name), "Wrong service connector.");
    final String schema = service.equals("account-service") ? "account_service" : "risk_service";
    require(text(connector.path("config"), "table.include.list").equals(schema + ".outbox"),
        "Wrong service outbox table.");
  }

  private static void topic(String description, String name) {
    require(Pattern.compile("Topic:\\s+" + Pattern.quote(name) + "(?:\\s|$)")
        .matcher(description).find(), "Missing internal topic: " + name);
    configuration(description, "ReplicationFactor:\\s*", 3);
    configuration(description, "min\\.insync\\.replicas=", 2);
  }

  private static void configuration(String description, String key, int expected) {
    final Matcher values = Pattern.compile("\\b" + key + "([0-9]+)").matcher(description);
    boolean found = false;
    while (values.find()) {
      require(Integer.parseInt(values.group(1)) == expected, "Wrong internal topic durability.");
      found = true;
    }
    require(found, "Missing internal topic durability.");
  }
}
