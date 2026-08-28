package com.simplematch.quickfixgateway.config;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configures the v2 admission identity supplied by the FIX ingress boundary. */
@ConfigurationProperties("simplematch.quickfix-gateway.ingress")
public record QuickFixGatewayIngressProperties(String venueMic, LocalDate tradingDay) {
  /** Defaults the venue while requiring the deployment-owned trading-day identity. */
  public QuickFixGatewayIngressProperties {
    venueMic = venueMic == null || venueMic.isBlank() ? "XTAI" : venueMic;
    Objects.requireNonNull(tradingDay, "tradingDay");
  }
}
