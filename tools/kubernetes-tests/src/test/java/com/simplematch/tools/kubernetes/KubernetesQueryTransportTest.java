package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Exercises real socket reuse and refuses to interpret HTTP timeouts as revocation. */
class KubernetesQueryTransportTest {
  @Test
  void reusedSocketRemainsUsableUntilTheObservedBlockedResponse() throws Exception {
    try (var server = new ServerSocket(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setSoTimeout(3_000);
      final var served = executor.submit(() -> {
        try (var client = server.accept()) {
          client.setSoTimeout(3_000);
          final var incoming = new BufferedReader(new InputStreamReader(
              client.getInputStream(), StandardCharsets.US_ASCII));
          for (int index = 0; index < 3; index++) {
            assertEquals("GET /api/v1/orders/order HTTP/1.1", incoming.readLine());
            for (String header = incoming.readLine(); !header.isEmpty();
                header = incoming.readLine()) {
              // Fully read each request so that every response uses this one socket.
            }
            final int status = index == 2 ? 503 : 200;
            client.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\n"
                + "Content-Length: 2\r\nConnection: keep-alive\r\n\r\n{}")
                .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
          }
        }
        return true;
      });
      try (var peer = new KubernetesQueryTransport.HttpPeer(server.getLocalPort())) {
        assertEquals(200, peer.request("/api/v1/orders/order", "synthetic", true));
        assertEquals(200, peer.request("/api/v1/orders/order", "synthetic", true));
        assertEquals("http-503", KubernetesQueryTransport.blocked(
            () -> peer.request("/api/v1/orders/order", "synthetic", false)));
      }
      assertEquals(true, served.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void timeoutIsNotAProofOfBlockedTransport() throws Exception {
    assertThrows(IllegalStateException.class,
        () -> KubernetesQueryTransport.blocked(() -> {
          throw new SocketTimeoutException("no response");
        }));
    assertEquals("connection-closed", KubernetesQueryTransport.blocked(() -> {
      throw new EOFException("remote closed");
    }));
    assertThrows(AssertionError.class, () -> KubernetesQueryTransport.blocked(() -> 200));
  }

  @Test
  void directPodDetachmentRequiresPriorVethAndActualInterfaceRemoval() {
    final var attached = new KubernetesQueryTransport.DirectRoute("veth7bbae1e0", "");
    final var fallback = new KubernetesQueryTransport.DirectRoute("eth0", "172.19.0.1");
    assertTrue(attached.isAttachedPodInterface());
    assertTrue(KubernetesQueryTransport.routeDetached(attached, fallback, true, true));
    assertTrue(KubernetesQueryTransport.routeDetached(attached,
        new KubernetesQueryTransport.DirectRoute("eth0", ""), true, true),
        "a detached Pod veth can fall back to an on-link eth0 route without a gateway");
    assertFalse(KubernetesQueryTransport.routeDetached(attached, fallback, false, true));
    assertFalse(KubernetesQueryTransport.routeDetached(attached, fallback, true, false),
        "an exact /32 route to the old Pod IP cannot establish detachment");
    assertFalse(KubernetesQueryTransport.routeDetached(attached, attached, true, true));
    assertFalse(KubernetesQueryTransport.routeDetached(attached,
        new KubernetesQueryTransport.DirectRoute("eth0", ""), false, true));
    assertFalse(KubernetesQueryTransport.routeDetached(attached,
        new KubernetesQueryTransport.DirectRoute("lo", ""), true, true));
    assertFalse(KubernetesQueryTransport.routeDetached(attached,
        new KubernetesQueryTransport.DirectRoute("cni0", ""), true, true));
    assertFalse(KubernetesQueryTransport.routeDetached(
        new KubernetesQueryTransport.DirectRoute("eth0", ""), fallback, true, true));
  }
}
