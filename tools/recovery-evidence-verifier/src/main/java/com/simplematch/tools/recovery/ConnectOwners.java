package com.simplematch.tools.recovery;

import static com.simplematch.tools.recovery.ReportFields.integer;
import static com.simplematch.tools.recovery.ReportFields.require;
import static com.simplematch.tools.recovery.ReportFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Resolves the running Account task to one Ready, movable Connect Pod. */
final class ConnectOwners {
  Owner resolve(JsonNode status, JsonNode pods) {
    final Task task = task(status);
    final String worker = task.worker();
    final Pod owner = readyPods(pods).get(host(worker));
    if (owner == null) {
      throw new IllegalArgumentException("Task worker does not identify one Ready Connect Pod.");
    }
    return new Owner(task.id(), worker, owner);
  }

  static Map<String, Pod> readyPods(JsonNode document) {
    require(document.path("items").isArray(), "Missing Connect Pod list.");
    final Map<String, Pod> result = new HashMap<>();
    final Set<String> nodes = new HashSet<>();
    final Set<String> identifiers = new HashSet<>();
    for (JsonNode pod : document.path("items")) {
      if (!ready(pod) || (!pod.path("metadata").path("deletionTimestamp").isNull()
          && !pod.path("metadata").path("deletionTimestamp").isMissingNode())) {
        continue;
      }
      final JsonNode labels = pod.path("metadata").path("labels");
      require(text(labels, "app.kubernetes.io/name").equals("kafka-connect")
          && text(labels, "app.kubernetes.io/component").equals("connector"),
          "Connect Pod labels are missing.");
      for (JsonNode volume : pod.path("spec").path("volumes")) {
        require(volume.path("persistentVolumeClaim").isMissingNode()
            || volume.path("persistentVolumeClaim").isNull(),
            "Connect Pod uses node-local storage.");
      }
      final Pod identity = new Pod(text(pod.path("metadata"), "name"),
          text(pod.path("metadata"), "uid"), text(pod.path("spec"), "nodeName"),
          text(pod.path("status"), "podIP"));
      require(result.putIfAbsent(identity.ip(), identity) == null,
          "Duplicate Connect Pod address.");
      nodes.add(identity.node());
      identifiers.add(identity.uid());
    }
    require(result.size() == 2 && nodes.size() == 2 && identifiers.size() == 2,
        "Expected two Ready Connect Pods on distinct nodes.");
    return result;
  }

  static boolean ready(JsonNode pod) {
    for (JsonNode condition : pod.path("status").path("conditions")) {
      if (condition.path("type").asText().equals("Ready")
          && condition.path("status").asText().equals("True")) {
        return true;
      }
    }
    return false;
  }

  static void reassigned(Owner before, Owner after) {
    require(before.taskId() == after.taskId(), "Connect task identity changed.");
    require(!before.workerId().equals(after.workerId()), "Connect worker did not change.");
    require(!before.pod().uid().equals(after.pod().uid()), "Connect Pod UID did not change.");
    require(!before.pod().name().equals(after.pod().name()),
        "Original owner Pod is still present.");
  }

  static void matches(JsonNode status, Owner owner) {
    final Task task = task(status);
    require(task.id() == owner.taskId() && task.worker().equals(owner.workerId()),
        "Task owner disagrees with Connect status.");
  }

  private static Task task(JsonNode status) {
    require(text(status, "name").equals("account-service-outbox"), "Wrong connector.");
    require(text(status.path("connector"), "state").equals("RUNNING"), "Connector is not running.");
    final JsonNode tasks = status.path("tasks");
    require(tasks.isArray() && tasks.size() == 1, "Expected one running Connect task.");
    final JsonNode task = tasks.get(0);
    require(text(task, "state").equals("RUNNING"), "Connect task is not running.");
    return new Task(integer(task, "id"), text(task, "worker_id"));
  }

  private record Task(int id, String worker) {}

  private static String host(String worker) {
    return worker.matches("[^:]+:[0-9]+") ? worker.substring(0, worker.lastIndexOf(':')) : worker;
  }

  record Pod(String name, String uid, String node, String ip) {}

  record Owner(int taskId, String workerId, Pod pod) {
    ObjectNode json() {
      final ObjectNode result = JsonReports.object();
      result.put("task_id", taskId);
      result.put("worker_id", workerId);
      result.put("worker_host", pod.ip());
      result.put("pod", pod.name());
      result.put("pod_uid", pod.uid());
      result.put("node", pod.node());
      result.put("pod_ip", pod.ip());
      result.put("ready", true);
      return result;
    }

    static Owner read(JsonNode value) {
      ReportFields.flag(value, "ready", true);
      final Owner owner = new Owner(integer(value, "task_id"), text(value, "worker_id"),
          new Pod(text(value, "pod"), text(value, "pod_uid"), text(value, "node"),
              text(value, "pod_ip")));
      require(host(owner.workerId()).equals(owner.pod().ip())
          && text(value, "worker_host").equals(owner.pod().ip()), "Wrong task worker address.");
      return owner;
    }
  }
}
