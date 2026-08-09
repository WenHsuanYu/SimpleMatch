package com.simplematch.quickfixgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configures the v2 admission identity supplied by the FIX ingress boundary. */
@ConfigurationProperties("simplematch.quickfix-gateway.ingress")
public record QuickFixGatewayIngressProperties(String venueMic) {
  /** Defaults phase-one FIX ingress to the Taiwan Stock Exchange MIC. */
  public QuickFixGatewayIngressProperties {
    venueMic = venueMic == null || venueMic.isBlank() ? "XTAI" : venueMic;
  }
}
