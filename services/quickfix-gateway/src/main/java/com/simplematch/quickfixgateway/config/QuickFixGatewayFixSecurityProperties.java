package com.simplematch.quickfixgateway.config;

import com.simplematch.quickfixgateway.fix.FixCounterparty;
import java.util.List;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Owns secret FIX counterparty policy and the passwords for explicitly configured TLS stores. */
@ConfigurationProperties("simplematch.quickfix-gateway.fix-security")
public final class QuickFixGatewayFixSecurityProperties {
  private final List<FixCounterparty> counterparties;
  private final String keyStorePassword;
  private final String trustStorePassword;

  /**
   * Binds external FIX secrets; an absent counterparty list denies all Logons.
   *
   * @param counterparties complete FIX identities with credentials and account allowlists
   * @param keyStorePassword password for the configured FIX TLS private-key store
   * @param trustStorePassword password for the configured FIX client-certificate truststore
   */
  public QuickFixGatewayFixSecurityProperties(
      List<FixCounterparty> counterparties, String keyStorePassword, String trustStorePassword) {
    this.counterparties = counterparties == null ? List.of() : List.copyOf(counterparties);
    this.keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
    this.trustStorePassword = trustStorePassword == null ? "" : trustStorePassword;
  }

  /** Returns the immutable, secret-provisioned counterparty policies. */
  public List<FixCounterparty> counterparties() {
    return counterparties;
  }

  /** Returns a diagnostic summary without exposing counterparties or TLS passwords. */
  @Override
  public String toString() {
    return "QuickFixGatewayFixSecurityProperties[counterpartyCount="
        + counterparties.size()
        + ", keyStorePassword=<redacted>, trustStorePassword=<redacted>]";
  }

  /** Returns only the whitelisted variables referenced by the QuickFIX TLS configuration. */
  public Properties quickfixVariables() {
    final Properties variables = new Properties();
    variables.setProperty("FIX_KEY_STORE_PASSWORD", keyStorePassword);
    variables.setProperty("FIX_TRUST_STORE_PASSWORD", trustStorePassword);
    return variables;
  }
}
