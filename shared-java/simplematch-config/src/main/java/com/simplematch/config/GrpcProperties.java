package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable gRPC target capability for synchronous service calls.
 *
 * @param targets targets for synchronously invoked platform services
 */
@ConfigurationProperties("simplematch.grpc")
public record GrpcProperties(GrpcTargetsProperties targets) {
  /** Normalizes an absent target group to the canonical local service targets. */
  public GrpcProperties {
    targets = targets == null ? GrpcTargetsProperties.defaults() : targets;
  }

  static GrpcProperties defaults() {
    return new GrpcProperties(null);
  }

  /**
   * Defines canonical gRPC targets for synchronously invoked platform services.
   *
   * @param accountService account-service target
   * @param riskService risk-service target
   */
  public record GrpcTargetsProperties(String accountService, String riskService) {
    /** Normalizes absent gRPC targets to local Kubernetes-compatible DNS targets. */
    public GrpcTargetsProperties {
      accountService =
          PlatformPropertyDefaults.string(accountService, "dns:///account-service:50051");
      riskService = PlatformPropertyDefaults.string(riskService, "dns:///risk-service:50052");
    }

    static GrpcTargetsProperties defaults() {
      return new GrpcTargetsProperties(null, null);
    }
  }
}
