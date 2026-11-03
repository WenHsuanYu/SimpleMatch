package com.simplematch.tools.kubernetes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns private disposable FIX identities for one account and their exact local cleanup. */
final class KubernetesFixCredentials implements AutoCloseable {
  private static final List<String> FILES = List.of(
      "server.p12", "client.p12", "server.crt", "client.crt", "clients.p12", "servers.p12");
  private final Path directory;
  private final UUID account;
  private final String username = "certification-" + UUID.randomUUID();
  private final String password = UUID.randomUUID().toString();
  private final String storePassword = UUID.randomUUID().toString();

  private KubernetesFixCredentials(Path directory, UUID account) {
    this.directory = directory;
    this.account = java.util.Objects.requireNonNull(account);
  }

  /** Creates new owner-private material, without adopting an existing directory or store. */
  static KubernetesFixCredentials create(Path directory, UUID account) throws Exception {
    final var credentials = new KubernetesFixCredentials(directory.toAbsolutePath(), account);
    Files.createDirectory(credentials.directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    try {
      credentials.provision();
      return credentials;
    } catch (Exception failure) {
      try {
        credentials.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void provision() throws Exception {
    for (String peer : List.of("server", "client")) {
      keytool("-genkeypair", "-keystore", peer + ".p12", "-alias", peer,
          "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
          "-ext", "SAN=dns:localhost,ip:127.0.0.1",
          "-ext", "EKU=" + ("server".equals(peer) ? "serverAuth" : "clientAuth"));
      keytool("-exportcert", "-keystore", peer + ".p12", "-alias", peer,
          "-rfc", "-file", peer + ".crt");
    }
    keytool("-importcert", "-keystore", "clients.p12", "-alias", "client", "-file", "client.crt");
    keytool("-importcert", "-keystore", "servers.p12", "-alias", "server", "-file", "server.crt");
    for (String name : FILES) {
      Files.setPosixFilePermissions(directory.resolve(name),
          PosixFilePermissions.fromString("rw-------"));
    }
  }

  private void keytool(String... arguments) throws Exception {
    final var command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
    command.addAll(List.of(arguments));
    command.addAll(List.of("-storetype", "PKCS12", "-storepass:env",
        "SIMPLEMATCH_FIX_STORE_PASSWORD", "-noprompt"));
    new BoundedCommand(directory, Map.of("SIMPLEMATCH_FIX_STORE_PASSWORD", storePassword))
        .run(command, "", Duration.ofSeconds(30)).checked();
  }

  ObjectNode gatewaySecret(String dsn) {
    final String prefix = "simplematch.quickfix-gateway.fix-security.";
    final String properties = String.join("\n",
        prefix + "key-store-password=" + storePassword,
        prefix + "trust-store-password=" + storePassword,
        prefix + "counterparties[0].session-id=FIX.4.4:SIMPLEMATCH->CLIENT",
        prefix + "counterparties[0].username=" + username,
        prefix + "counterparties[0].password=" + password,
        prefix + "counterparties[0].account-ids[0]=" + account, "");
    return KubernetesDocuments.data("Secret", "quickfix-gateway-secrets", Map.of(
        "postgres_dsn", dsn, "quickfix-gateway.properties", properties));
  }

  ObjectNode tlsSecret() throws IOException {
    final var secret = KubernetesDocuments.data("Secret", "quickfix-gateway-fix-tls", Map.of());
    secret.remove("stringData");
    final var data = secret.putObject("data");
    for (String name : List.of("server.p12", "clients.p12")) {
      data.put(name,
          Base64.getEncoder().encodeToString(Files.readAllBytes(directory.resolve(name))));
    }
    return secret;
  }

  Map<String, String> clientEnvironment() {
    return Map.of(
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE", directory.resolve("client.p12").toString(),
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD", storePassword,
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE", directory.resolve("servers.p12").toString(),
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD", storePassword,
        "SIMPLEMATCH_LIVE_FIX_USERNAME", username,
        "SIMPLEMATCH_LIVE_FIX_PASSWORD", password);
  }

  /** Removes only the six files created in this invocation's fresh directory. */
  @Override
  public void close() throws IOException {
    for (String name : FILES) {
      Files.deleteIfExists(directory.resolve(name));
    }
    Files.deleteIfExists(directory);
  }
}
