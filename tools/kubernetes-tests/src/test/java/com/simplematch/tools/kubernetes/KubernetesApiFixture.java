package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Actual kubectl discovery/HTTP seam; it does not claim to be deployed Kubernetes. */
final class KubernetesApiFixture implements AutoCloseable {
  final ObjectMapper json = new ObjectMapper();
  final List<JsonNode> deletions = new CopyOnWriteArrayList<>();
  final List<String> requests = new CopyOnWriteArrayList<>();
  private final Map<String, byte[]> documents = new ConcurrentHashMap<>();
  private final HttpServer server;
  private final Path directory;

  KubernetesApiFixture(Path directory) throws Exception {
    this.directory = directory;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    put("/api", Map.of("kind", "APIVersions", "versions", List.of("v1")));
    put("/apis", Map.of("kind", "APIGroupList", "groups", List.of(
        Map.of("name", "batch", "versions", List.of(version("batch", "v1")),
            "preferredVersion", version("batch", "v1")),
        Map.of("name", "storage.k8s.io", "versions", List.of(version("storage.k8s.io", "v1")),
            "preferredVersion", version("storage.k8s.io", "v1")))));
    put("/api/v1", Map.of("kind", "APIResourceList", "groupVersion", "v1", "resources",
        List.of(resource("namespaces", "Namespace", false), resource("pods", "Pod", true),
            resource("nodes", "Node", false), resource("persistentvolumes", "PersistentVolume", false))));
    put("/apis/batch/v1", Map.of("kind", "APIResourceList", "groupVersion", "batch/v1",
        "resources", List.of(resource("jobs", "Job", true))));
    put("/apis/storage.k8s.io/v1", Map.of("kind", "APIResourceList",
        "groupVersion", "storage.k8s.io/v1", "resources",
        List.of(resource("storageclasses", "StorageClass", false))));
    put("/api/v1/persistentvolumes", Map.of("apiVersion", "v1", "kind", "PersistentVolumeList",
        "items", List.of()));
    put("/api/v1/namespaces", Map.of("apiVersion", "v1", "kind", "NamespaceList", "items", List.of()));
    documents.put("/readyz", "readyz check passed".getBytes(StandardCharsets.UTF_8));
    server.createContext("/", exchange -> {
      requests.add(exchange.getRequestURI().toString());
      final String path = exchange.getRequestURI().getPath();
      byte[] body = documents.get(path);
      int status = 200;
      if (body == null) {
        status = 404;
        body = json.writeValueAsBytes(Map.of("apiVersion", "v1", "kind", "Status",
            "status", "Failure", "reason", "NotFound", "code", 404));
      } else if ("DELETE".equals(exchange.getRequestMethod())) {
        deletions.add(json.readTree(exchange.getRequestBody()));
        documents.remove(path);
      }
      exchange.getResponseHeaders().set("Content-Type", path.endsWith("/log")
          || path.equals("/readyz") ? "text/plain" : "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  void put(String path, Object body) throws Exception {
    documents.put(path, json.writeValueAsBytes(body));
  }

  void log(String pod, String text) {
    documents.put("/api/v1/namespaces/scenario/pods/" + pod + "/log",
        text.getBytes(StandardCharsets.UTF_8));
  }

  KubernetesClient client() throws Exception {
    final Path config = directory.resolve("kubeconfig.json");
    Files.writeString(config, json.writeValueAsString(Map.of("apiVersion", "v1", "kind", "Config",
        "current-context", "scenario", "clusters", List.of(Map.of("name", "scenario", "cluster",
            Map.of("server", "http://127.0.0.1:" + server.getAddress().getPort()))),
        "contexts", List.of(Map.of("name", "scenario", "context", Map.of("cluster", "scenario",
            "user", "scenario"))), "users", List.of(Map.of("name", "scenario", "user", Map.of())))));
    return new KubernetesClient(directory, config, "scenario", "scenario");
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static Map<String, String> version(String group, String version) {
    return Map.of("groupVersion", group + "/" + version, "version", version);
  }

  private static Map<String, Object> resource(String name, String kind, boolean namespaced) {
    return Map.of("name", name, "singularName", kind.toLowerCase(java.util.Locale.ROOT), "kind", kind, "namespaced", namespaced,
        "verbs", List.of("get", "list", "delete"),
        "shortNames", name.equals("persistentvolumes") ? List.of("pv") : List.of());
  }
}
