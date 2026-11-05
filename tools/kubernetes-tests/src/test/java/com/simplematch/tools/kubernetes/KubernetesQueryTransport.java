package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Observes actual old-generation HTTP transports while their Pod forwarders remain owned. */
final class KubernetesQueryTransport implements AutoCloseable {
  private static final String DIRECT_PATH = "/api/v1/accounts/not-a-uuid/summary";
  private final List<Probe> probes = new ArrayList<>();
  private final BoundedCommand docker;
  private final DockerContext dockerContext;
  private final Path evidence;
  private final int version;
  private final String readerToken;
  private final ObjectMapper json = new ObjectMapper();

  private KubernetesQueryTransport(BoundedCommand docker, DockerContext dockerContext,
      Path evidence, int version, String readerToken) {
    this.docker = docker;
    this.dockerContext = dockerContext;
    this.evidence = evidence;
    this.version = version;
    this.readerToken = readerToken;
  }

  static KubernetesQueryTransport open(KubernetesScenarioTarget target, BoundedCommand docker,
      DockerContext dockerContext, Path evidence, int version, String readerToken,
      String orderId, List<KubernetesQueryPolicyReplacement.Owner> owners) throws Exception {
    final var transport = new KubernetesQueryTransport(
        docker, dockerContext, evidence, version, readerToken);
    try {
      assertEquals(2, owners.size(), "both Query Pod transports must be observed");
      for (var owner : owners) {
        final var forward = target.client().forward("pod/" + owner.name(), 8086,
            evidence.resolve("v" + version + "-" + owner.name() + "-old-forward.log"));
        transport.probes.add(new Probe(owner, forward));
        final var probe = transport.probes.getLast();
        probe.connection = new HttpPeer(forward.port());
        final String path = "/api/v1/orders/" + orderId;
        assertEquals(200, probe.connection.request(path, readerToken, true));
        // Two responses on the very same socket establish usable HTTP/1.1 keep-alive.
        assertEquals(200, probe.connection.request(path, readerToken, true));
        assertEquals(400, transport.direct(probe.owner),
            "Query direct Pod path was blocked before stopping the generation");
        probe.directRoute = transport.route(probe.owner);
        if (!probe.directRoute.isAttachedPodInterface()) {
          throw new IllegalStateException(
              "Query direct Pod address has no attached node-local veth before stop");
        }
      }
      return transport;
    } catch (Exception | AssertionError failure) {
      try {
        transport.close();
      } catch (Exception closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  void verifyBlocked() throws Exception {
    final var results = new ArrayList<Map<String, Object>>();
    for (var probe : probes) {
      final String path = "/api/v1/accounts/not-a-uuid/summary";
      final String existing = blocked(() -> probe.connection.request(path, readerToken, false));
      final String fresh = blocked(() -> {
        try (var connection = new HttpPeer(probe.forward.port())) {
          return connection.request(path, readerToken, false);
        }
      });
      // kindnet withdraws its per-Pod veth route when CNI releases the sandbox.
      // A TCP probe against that now-unrouted IP can time out through the Docker
      // gateway; a timeout is not proof of revocation. Instead prove that the
      // exact interface which successfully routed the earlier HTTP request is
      // gone and the old Pod IP no longer has a local delivery route.
      final var after = route(probe.owner);
      final boolean interfaceGone = interfaceGone(probe.owner, probe.directRoute.device());
      if (!routeDetached(probe.directRoute, after, interfaceGone)) {
        throw new IllegalStateException("old Query direct Pod route remains attached or its "
            + "detachment was not conclusively observed");
      }
      results.add(Map.of("pod", probe.owner.name(), "uid", probe.owner.uid(),
          "containerId", probe.owner.containerId(), "existingConnection", existing,
          "newConnection", fresh, "directPodPath", "node-veth-detached",
          "directRouteBefore", probe.directRoute,
          "directRouteAfter", after, "oldInterfaceAbsent", true));
    }
    Files.write(evidence.resolve("v" + version + "-transport-block.json"),
        json.writeValueAsBytes(results));
  }

  private int direct(KubernetesQueryPolicyReplacement.Owner owner) throws Exception {
    final String ip = owner.podIp();
    assertFalse(ip.isBlank() || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"),
        "Query Pod lacks a numeric direct IPv4 address");
    final String input = "url = \"http://" + ip + ":8086" + DIRECT_PATH + "\"\n"
        + "header = \"Authorization: Bearer " + readerToken + "\"\n";
    final var result = docker.run(dockerContext.command(
        "exec", "-i", owner.node(), "curl", "--noproxy", "*", "--silent", "--show-error",
        "--max-time", "2", "--write-out", "\\n%{http_code}", "--config", "-"),
        input, Duration.ofSeconds(4));
    if (result.exitCode() == 7 && !result.truncated()) {
      return 0; // curl's concrete connection-refused result, not a timeout.
    }
    if (result.exitCode() != 0 || result.truncated()) {
      throw new IllegalStateException(
          "direct Query transport was not conclusively observed: curl exit " + result.exitCode());
    }
    final String code = result.output().lines().reduce((left, right) -> right).orElse("");
    if (!code.matches("[0-9]{3}")) {
      throw new IllegalStateException("direct Query transport has no HTTP status");
    }
    return Integer.parseInt(code);
  }

  private DirectRoute route(KubernetesQueryPolicyReplacement.Owner owner) throws Exception {
    final String ip = owner.podIp();
    if (!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
      throw new IllegalArgumentException("Query route has no numeric Pod IPv4 address");
    }
    final String output = docker.run(dockerContext.command("exec", owner.node(),
        "ip", "-j", "-4", "route", "get", ip), "", Duration.ofSeconds(4)).checked();
    final var routes = json.readTree(output);
    if (!routes.isArray() || routes.size() != 1
        || !ip.equals(routes.get(0).path("dst").asText())) {
      throw new IllegalStateException("Query worker returned an unexpected direct Pod route");
    }
    final var entry = routes.get(0);
    final String device = entry.path("dev").asText();
    if (device.isBlank()) {
      throw new IllegalStateException("Query direct Pod route has no network device");
    }
    return new DirectRoute(device, entry.path("gateway").asText());
  }

  private boolean interfaceGone(KubernetesQueryPolicyReplacement.Owner owner, String device)
      throws Exception {
    if (!device.matches("veth[0-9a-f]{8}")) {
      throw new IllegalArgumentException("unexpected kindnet Pod device identity");
    }
    final var result = docker.run(dockerContext.command("exec", owner.node(),
        "test", "!", "-e", "/sys/class/net/" + device), "", Duration.ofSeconds(4));
    return result.exitCode() == 0 && !result.truncated();
  }

  static boolean routeDetached(DirectRoute before, DirectRoute after, boolean interfaceGone) {
    return before.isAttachedPodInterface() && interfaceGone
        && !before.device().equals(after.device())
        && "eth0".equals(after.device()) && !after.gateway().isBlank();
  }

  record DirectRoute(String device, String gateway) {
    boolean isAttachedPodInterface() {
      return device.matches("veth[0-9a-f]{8}") && gateway.isBlank();
    }
  }

  @FunctionalInterface
  interface Request {
    int send() throws Exception;
  }

  static String blocked(Request request) throws Exception {
    try {
      final int status = request.send();
      assertEquals(503, status, "old Query generation still serves an HTTP request");
      return "http-503";
    } catch (SocketTimeoutException timeout) {
      throw new IllegalStateException("old Query HTTP Block timed out without evidence", timeout);
    } catch (ConnectException | EOFException failure) {
      return "connection-closed";
    } catch (SocketException failure) {
      final String reason = failure.getMessage();
      if (reason == null || !(reason.contains("Connection reset")
          || reason.contains("Broken pipe") || reason.contains("Socket closed")
          || reason.contains("Connection aborted"))) {
        throw failure;
      }
      return "connection-reset";
    }
  }

  @Override
  public void close() throws Exception {
    Exception error = null;
    for (int index = probes.size() - 1; index >= 0; index--) {
      final var probe = probes.get(index);
      try {
        if (probe.connection != null) {
          probe.connection.close();
        }
      } catch (Exception failure) {
        if (error == null) {
          error = failure;
        } else {
          error.addSuppressed(failure);
        }
      }
      try {
        probe.forward.close();
      } catch (Exception failure) {
        if (error == null) {
          error = failure;
        } else {
          error.addSuppressed(failure);
        }
      }
    }
    if (error != null) {
      throw error;
    }
  }

  private static final class Probe {
    private final KubernetesQueryPolicyReplacement.Owner owner;
    private final KubernetesPortForward forward;
    private HttpPeer connection;
    private DirectRoute directRoute;

    private Probe(KubernetesQueryPolicyReplacement.Owner owner, KubernetesPortForward forward) {
      this.owner = owner;
      this.forward = forward;
    }
  }

  /** Consumes full successful responses before reusing one authenticated socket. */
  static final class HttpPeer implements AutoCloseable {
    private final Socket socket = new Socket();
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    HttpPeer(int port) throws Exception {
      try {
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
        socket.setSoTimeout(2_000);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
      } catch (Exception failure) {
        socket.close();
        throw failure;
      }
    }

    int request(String path, String bearer, boolean reuse) throws Exception {
      output.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
          + "Authorization: Bearer " + bearer + "\r\nConnection: keep-alive\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      output.flush();
      final String statusLine = line(input);
      if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3} .*")) {
        throw new IOException("Query transport returned an invalid HTTP status line");
      }
      final int status = Integer.parseInt(statusLine.substring(9, 12));
      long contentLength = -1;
      boolean chunked = false;
      boolean closing = false;
      for (String header = line(input); !header.isEmpty(); header = line(input)) {
        final String normalized = header.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("content-length:")) {
          contentLength = Long.parseLong(header.substring(header.indexOf(':') + 1).trim());
        } else if (normalized.startsWith("transfer-encoding:")) {
          chunked = normalized.contains("chunked");
        } else if (normalized.equals("connection: close")) {
          closing = true;
        }
      }
      if (reuse) {
        if (closing) {
          throw new IOException("Query did not preserve HTTP keep-alive");
        }
        if (chunked) {
          int consumed = 0;
          while (true) {
            final String sizeLine = line(input).split(";", 2)[0];
            final int chunk = Integer.parseInt(sizeLine, 16);
            if (chunk == 0) {
              while (!line(input).isEmpty()) {
                // Consume trailers to preserve framing of the next response.
              }
              break;
            }
            consumed += chunk;
            if (consumed > 262_144) {
              throw new IOException("Query keep-alive body exceeds verification limit");
            }
            if (input.readNBytes(chunk).length != chunk) {
              throw new EOFException("Query chunk body terminated early");
            }
            if (!line(input).isEmpty()) {
              throw new IOException("Query chunk terminator is invalid");
            }
          }
        } else if (contentLength >= 0 && contentLength <= 262_144) {
          if (input.readNBytes((int) contentLength).length != contentLength) {
            throw new EOFException("Query keep-alive body terminated early");
          }
        } else {
          throw new IOException("Query keep-alive response has ambiguous body framing");
        }
      }
      return status;
    }

    private static String line(BufferedInputStream source) throws IOException {
      final var bytes = new ByteArrayOutputStream();
      while (true) {
        final int value = source.read();
        if (value < 0) {
          throw new EOFException("Query HTTP connection closed");
        }
        if (value == '\n') {
          final byte[] data = bytes.toByteArray();
          final int length = data.length > 0 && data[data.length - 1] == '\r'
              ? data.length - 1 : data.length;
          return new String(data, 0, length, StandardCharsets.US_ASCII);
        }
        if (bytes.size() >= 8192) {
          throw new IOException("Query HTTP header line exceeded verification limit");
        }
        bytes.write(value);
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
