package com.simplematch.quickfixgateway.fix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import quickfix.SessionID;
import quickfix.fix44.Logon;

/** Provisions disposable test credentials and a self-signed TLS identity for socket simulators. */
final class FixTestSecurity {
  private FixTestSecurity() {}

  static FixSessionAccess access(String accountId, OrderSessionRegistry registry) {
    return new FixSessionAccess(
        List.of(
            new FixCounterparty(
                new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT").toString(),
                "test-trader",
                "test-password",
                Set.of(accountId)),
            new FixCounterparty(
                new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT1").toString(),
                "test-trader",
                "test-password",
                Set.of(accountId))),
        registry);
  }

  static Logon logon() {
    final Logon logon = new Logon();
    logon.setString(553, "test-trader");
    logon.setString(554, "test-password");
    return logon;
  }

  static Map<String, String> clientEnvironment(Path directory) throws Exception {
    final String store = tlsStore(directory).toString();
    return Map.of(
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE", store,
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD", "test-password",
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE", store,
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD", "test-password",
        "SIMPLEMATCH_LIVE_FIX_USERNAME", "test-trader",
        "SIMPLEMATCH_LIVE_FIX_PASSWORD", "test-password");
  }

  static String acceptorSettings(Path directory) throws Exception {
    return """
    SocketUseSSL=Y
    NeedClientAuth=Y
    KeyStoreType=PKCS12
    TrustStoreType=PKCS12
    SocketKeyStore=%1$s
    SocketKeyStorePassword=test-password
    SocketTrustStore=%1$s
    SocketTrustStorePassword=test-password
    LogMessageWhenSessionNotFound=N
    """
        .formatted(tlsStore(directory));
  }

  static Path tlsStore(Path directory) throws IOException, InterruptedException {
    final Path store = directory.resolve("fix-test-identity.p12").toAbsolutePath();
    if (!Files.exists(store)) {
      final Process keytool =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "fix-test",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "SAN=dns:localhost,ip:127.0.0.1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  store.toString(),
                  "-storepass",
                  "test-password",
                  "-keypass",
                  "test-password",
                  "-noprompt")
              .redirectErrorStream(true)
              .start();
      final String output =
          new String(
              keytool.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (keytool.waitFor() != 0) {
        throw new IOException("could not provision simulator TLS identity: " + output);
      }
    }
    return store;
  }

  static SSLContext clientContext(Path trustedServerStore, Path clientIdentity) throws Exception {
    final KeyStore trustStore = loadStore(trustedServerStore);
    final TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(trustStore);
    KeyManager[] keys = new KeyManager[0];
    if (clientIdentity != null) {
      final KeyManagerFactory identity =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      identity.init(loadStore(clientIdentity), "test-password".toCharArray());
      keys = identity.getKeyManagers();
    }
    final SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys, trust.getTrustManagers(), null);
    return context;
  }

  private static KeyStore loadStore(Path path) throws Exception {
    final KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(path)) {
      store.load(input, "test-password".toCharArray());
    }
    return store;
  }
}
