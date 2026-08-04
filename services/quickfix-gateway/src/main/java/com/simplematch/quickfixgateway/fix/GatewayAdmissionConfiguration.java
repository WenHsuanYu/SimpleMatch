package com.simplematch.quickfixgateway.fix;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the gateway admission state shared by the new and cancel FIX paths. */
@Configuration
public class GatewayAdmissionConfiguration {
  @Bean
  GatewayAdmissionGate gatewayAdmissionGate() {
    return new GatewayAdmissionGate();
  }
}
