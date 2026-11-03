package com.simplematch.quickfixgateway.fix;

import java.util.function.Function;
import quickfix.SessionSettings;

/**
 * Supplies live certification clients with the same mandatory TLS and Logon controls as ingress.
 */
final class FixInitiatorSecurity {
  private FixInitiatorSecurity() {}

  static void configure(SessionSettings settings, Function<String, String> environment) {
    settings.setString("SocketUseSSL", "Y");
    settings.setString("KeyStoreType", "PKCS12");
    settings.setString("TrustStoreType", "PKCS12");
    settings.setString("EndpointIdentificationAlgorithm", "HTTPS");
    settings.setString("SocketKeyStore", required(environment, "SIMPLEMATCH_LIVE_FIX_KEY_STORE"));
    settings.setString(
        "SocketKeyStorePassword", required(environment, "SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD"));
    settings.setString(
        "SocketTrustStore", required(environment, "SIMPLEMATCH_LIVE_FIX_TRUST_STORE"));
    settings.setString(
        "SocketTrustStorePassword",
        required(environment, "SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD"));
    settings.setString("LogonTag", "553=" + required(environment, "SIMPLEMATCH_LIVE_FIX_USERNAME"));
    settings.setString(
        "LogonTag1", "554=" + required(environment, "SIMPLEMATCH_LIVE_FIX_PASSWORD"));
  }

  private static String required(Function<String, String> environment, String name) {
    final String value = environment.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required for authenticated FIX certification");
    }
    return value;
  }
}
