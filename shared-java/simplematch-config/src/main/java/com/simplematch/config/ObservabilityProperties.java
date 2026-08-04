package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently bindable telemetry and metrics capability.
 *
 * @param otel OpenTelemetry exporter settings
 * @param prometheus Prometheus-compatible metrics settings
 */
@ConfigurationProperties("simplematch.observability")
public record ObservabilityProperties(OtelProperties otel, PrometheusProperties prometheus) {
  /** Normalizes absent observability groups to local-development defaults. */
  public ObservabilityProperties {
    otel = otel == null ? OtelProperties.defaults() : otel;
    prometheus = prometheus == null ? PrometheusProperties.defaults() : prometheus;
  }

  static ObservabilityProperties defaults() {
    return new ObservabilityProperties(null, null);
  }

  /**
   * Defines the OpenTelemetry Protocol export endpoint.
   *
   * @param exporterOtlpEndpoint OTLP HTTP exporter endpoint
   */
  public record OtelProperties(String exporterOtlpEndpoint) {
    /** Normalizes an absent OTLP endpoint to the local collector endpoint. */
    public OtelProperties {
      exporterOtlpEndpoint =
          PlatformPropertyDefaults.string(exporterOtlpEndpoint, "http://localhost:4318");
    }

    static OtelProperties defaults() {
      return new OtelProperties(null);
    }
  }

  /**
   * Defines the port exposing Prometheus-compatible metrics.
   *
   * @param port Prometheus-compatible metrics port
   */
  public record PrometheusProperties(Integer port) {
    /** Normalizes an absent metrics port to the local exporter port. */
    public PrometheusProperties {
      port = PlatformPropertyDefaults.integerOrDefault(port, 9464);
    }

    static PrometheusProperties defaults() {
      return new PrometheusProperties(null);
    }
  }
}
