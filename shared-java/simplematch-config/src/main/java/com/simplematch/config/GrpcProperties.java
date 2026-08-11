package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Independently bindable gRPC target capability for synchronous service calls.
 *
 * @param targets targets for synchronously invoked platform services
 * @param security transport security for gRPC servers and clients
 */
@ConfigurationProperties("simplematch.grpc")
public record GrpcProperties(GrpcTargetsProperties targets, SecurityProperties security) {
  /** Preserves the one-argument construction seam used by local callers and tests. */
  public GrpcProperties(GrpcTargetsProperties targets) {
    this(targets, null);
  }

  /** Normalizes absent targets and security to canonical local defaults. */
  @ConstructorBinding
  public GrpcProperties {
    targets = targets == null ? GrpcTargetsProperties.defaults() : targets;
    security = security == null ? SecurityProperties.defaults() : security;
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

  /** Defines the mutually authenticated TLS material shared by gRPC peers. */
  public record SecurityProperties(
      boolean tlsEnabled,
      String certificatePath,
      String privateKeyPath,
      String trustCertificatePath) {
    /** Requires complete server/client certificate material when TLS is enabled. */
    public SecurityProperties {
      certificatePath = normalizePath(certificatePath);
      privateKeyPath = normalizePath(privateKeyPath);
      trustCertificatePath = normalizePath(trustCertificatePath);
      if (tlsEnabled
          && (certificatePath.isEmpty()
              || privateKeyPath.isEmpty()
              || trustCertificatePath.isEmpty())) {
        throw new IllegalArgumentException(
            "gRPC TLS requires certificate, private-key, and trust-certificate paths");
      }
    }

    static SecurityProperties defaults() {
      return new SecurityProperties(false, "", "", "");
    }

    private static String normalizePath(String path) {
      return path == null ? "" : path.trim();
    }
  }
}
