package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;

/** Uses the same body rules during observation and final report acceptance. */
final class PlacementHealth {
  private PlacementHealth() {}

  static ObjectNode observation(String stage, String name, int status, Path body)
      throws IOException {
    require(status == 200, "The application endpoint did not return HTTP 200.");
    final JsonNode content = JsonReports.read(body);
    check(name, content);
    final ObjectNode result = JsonReports.object();
    result.put("path", path(name));
    result.put("http_status", status);
    result.put("body_status", name.equals("serving") ? "SERVING" : "UP");
    result.put("authentication", name.equals("serving")
        ? "query-operations-read" : "anonymous-health");
    result.put("body_file", "health/" + stage + "-" + name + ".json");
    result.put("body_sha256", EvidenceFiles.sha256(body));
    return result;
  }

  static void check(String name, JsonNode body) {
    if (name.equals("serving")) {
      require(body.path("partitions").isArray(), "Freshness response lacks partitions.");
    } else {
      path(name);
      require(text(body, "status").equals("UP"), "The health endpoint did not report UP.");
    }
  }

  static String path(String name) {
    return switch (name) {
      case "readiness" -> "/actuator/health/readiness";
      case "liveness" -> "/actuator/health/liveness";
      case "serving" -> "/api/v1/freshness";
      default -> throw new IllegalArgumentException("Unknown application probe.");
    };
  }
}
