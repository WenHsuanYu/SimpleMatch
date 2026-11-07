package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the exact server Secret and client environment consumed by the public FIX scenario. */
class KubernetesFixCredentialsTest {
  @TempDir Path temporary;

  @Test
  void provisionsPrivateMutualTlsAndAccountBindingThenRemovesOnlyItsOwnMaterial() throws Exception {
    final Path directory = temporary.resolve("fix");
    final Path sibling = Files.writeString(temporary.resolve("unrelated"), "keep");
    final UUID account = UUID.randomUUID();
    final UUID counterparty = UUID.randomUUID();
    try (var credentials = KubernetesFixCredentials.create(directory, account, counterparty)) {
      assertEquals(PosixFilePermissions.fromString("rwx------"),
          Files.getPosixFilePermissions(directory));
      try (var files = Files.list(directory)) {
        for (Path file : files.toList()) {
          assertEquals(PosixFilePermissions.fromString("rw-------"),
              Files.getPosixFilePermissions(file));
        }
      }
      final var properties = new Properties();
      properties.load(new StringReader(credentials.gatewaySecret("postgres://fixture")
          .path("stringData").path("quickfix-gateway.properties").asText()));
      final String prefix = "simplematch.quickfix-gateway.fix-security";
      assertEquals(account.toString(), properties.getProperty(prefix + ".counterparties[0].account-ids[0]"));
      assertEquals(counterparty.toString(),
          properties.getProperty(prefix + ".counterparties[0].account-ids[1]"));
      final char[] password = properties.getProperty(prefix + ".key-store-password").toCharArray();
      final var stores = credentials.tlsSecret().path("data");
      final SSLContext server = context(Base64.getDecoder().decode(stores.path("server.p12").asText()),
          Base64.getDecoder().decode(stores.path("clients.p12").asText()), password);
      final var client = credentials.clientEnvironment();
      final byte[] trust = Files.readAllBytes(Path.of(client.get("SIMPLEMATCH_LIVE_FIX_TRUST_STORE")));
      final char[] clientPassword = client.get("SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD").toCharArray();
      assertEquals(properties.getProperty(prefix + ".counterparties[0].username"),
          client.get("SIMPLEMATCH_LIVE_FIX_USERNAME"));
      assertEquals(properties.getProperty(prefix + ".counterparties[0].password"),
          client.get("SIMPLEMATCH_LIVE_FIX_PASSWORD"));
      final SSLContext knownClient = context(Files.readAllBytes(
          Path.of(client.get("SIMPLEMATCH_LIVE_FIX_KEY_STORE"))), trust, clientPassword);
      assertTrue(exchange(server, knownClient));
      assertFalse(exchange(server, context(null, trust, clientPassword)));
      // Accept only the exact approved peer, not any otherwise well-formed client certificate.
      try (var foreign = KubernetesFixCredentials.create(temporary.resolve("foreign-fix"),
          UUID.randomUUID(), UUID.randomUUID())) {
        final var unrelatedClient = foreign.clientEnvironment();
        final SSLContext foreignContext = context(Files.readAllBytes(Path.of(
            unrelatedClient.get("SIMPLEMATCH_LIVE_FIX_KEY_STORE"))), trust,
            unrelatedClient.get("SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD").toCharArray(),
            clientPassword);
        assertFalse(exchange(server, foreignContext));
      }
      // Server identity must match the requested hostname, not just a trusted issuer.
      assertFalse(exchange(server, knownClient, "unapproved.example"));
    }
    assertFalse(Files.exists(directory));
    assertEquals("keep", Files.readString(sibling));
  }

  private static SSLContext context(byte[] identity, byte[] trust, char[] password) throws Exception {
    return context(identity, trust, password, password);
  }

  private static SSLContext context(byte[] identity, byte[] trust,
      char[] identityPassword, char[] trustPassword) throws Exception {
    final var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trusts.init(store(trust, trustPassword));
    final var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    if (identity != null) {
      keys.init(store(identity, identityPassword), identityPassword);
    }
    final var context = SSLContext.getInstance("TLS");
    context.init(identity == null ? new KeyManager[0] : keys.getKeyManagers(),
        trusts.getTrustManagers(), null);
    return context;
  }

  private static KeyStore store(byte[] contents, char[] password) throws Exception {
    final var store = KeyStore.getInstance("PKCS12");
    try (var input = new ByteArrayInputStream(contents)) {
      store.load(input, password);
    }
    return store;
  }

  private static boolean exchange(SSLContext serverContext, SSLContext clientContext) throws Exception {
    return exchange(serverContext, clientContext, "localhost");
  }

  private static boolean exchange(SSLContext serverContext, SSLContext clientContext,
      String peerHost) throws Exception {
    try (var server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setNeedClientAuth(true);
      server.setSoTimeout(5_000);
      final var accepted = executor.submit(() -> {
        try (var socket = (SSLSocket) server.accept()) {
          socket.setSoTimeout(5_000);
          socket.startHandshake();
          socket.getOutputStream().write(42);
          return true;
        } catch (IOException rejected) {
          return false;
        }
      });
      boolean received = false;
      try (var transport = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
          var client = (SSLSocket) clientContext.getSocketFactory()
              .createSocket(transport, peerHost, server.getLocalPort(), true)) {
        client.setSoTimeout(5_000);
        final var parameters = client.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSSLParameters(parameters);
        received = client.getInputStream().read() == 42;
      } catch (IOException rejected) {
        received = false;
      }
      return received && accepted.get(10, TimeUnit.SECONDS);
    }
  }
}
