package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Groups shared SimpleMatch platform settings bound from the Spring {@code Environment}.
 *
 * <p>Spring Boot binds values below the {@code simplematch} prefix into this immutable configurati-
 * on tree. Missing groups and blank scalar values are replaced with local-development defaults so
 * that services can start without duplicating platform configuration.
 *
 * <p>Defaults intentionally contain no credentials. Staging and production credentials remain owned
 * by approved external secret sources and are validated during application startup.
 *
 * @param environment logical deployment environment, such as {@code local}, {@code staging}, or
 *     {@code production}
 * @param kafka Kafka broker, topic, and partition settings
 * @param postgres PostgreSQL connection settings
 * @param redis Redis endpoint settings for rebuildable projections
 * @param grpc gRPC target settings for synchronous service communication
 * @param routing routing snapshot settings
 * @param observability telemetry and metrics exporter settings
 * @param market stable market-wide defaults
 */
@ConfigurationProperties("simplematch")
public record PlatformProperties(
    String environment,
    KafkaProperties kafka,
    PostgresProperties postgres,
    RedisProperties redis,
    GrpcProperties grpc,
    RoutingProperties routing,
    ObservabilityProperties observability,
    MarketProperties market) {

  /**
   * Normalizes absent configuration groups and blank values to local-development defaults.
   *
   * @param environment logical deployment environment
   * @param kafka Kafka settings
   * @param postgres PostgreSQL settings
   * @param redis Redis settings
   * @param grpc gRPC settings
   * @param routing routing settings
   * @param observability observability settings
   * @param market market settings
   */
  public PlatformProperties {
    environment = defaultString(environment, "local");
    kafka = kafka == null ? KafkaProperties.defaults() : kafka;
    postgres = postgres == null ? PostgresProperties.defaults() : postgres;
    redis = redis == null ? RedisProperties.defaults() : redis;
    grpc = grpc == null ? GrpcProperties.defaults() : grpc;
    routing = routing == null ? RoutingProperties.defaults() : routing;
    observability = observability == null ? ObservabilityProperties.defaults() : observability;
    market = market == null ? MarketProperties.defaults() : market;
  }

  /**
   * Returns the supplied non-blank value or the designated fallback.
   *
   * @param value configured value
   * @param fallback fallback used when the configured value is null or blank
   * @return configured value when present; otherwise the fallback
   */
  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * Returns the supplied integer or the designated positive fallback.
   *
   * <p>Range validation is intentionally delegated to startup validation so invalid configured
   * values are not silently replaced.
   *
   * @param value configured value
   * @param fallback fallback used when the configured value is null
   * @return configured value when present; otherwise the fallback
   */
  private static Integer defaultPositive(Integer value, int fallback) {
    return value == null ? Integer.valueOf(fallback) : value;
  }

  /**
   * Groups Kafka connectivity, topic, and partition settings shared by event-producing services.
   *
   * @param brokers comma-separated Kafka bootstrap servers
   * @param topics canonical topic names
   * @param partitions desired partition counts for ordered event streams
   */
  public record KafkaProperties(
      String brokers, TopicsProperties topics, PartitionsProperties partitions) {

    /**
     * Normalizes absent Kafka values to local-development defaults.
     *
     * @param brokers comma-separated Kafka bootstrap servers
     * @param topics canonical topic names
     * @param partitions desired partition counts
     */
    public KafkaProperties {
      brokers = defaultString(brokers, "localhost:9092");
      topics = topics == null ? TopicsProperties.defaults() : topics;
      partitions = partitions == null ? PartitionsProperties.defaults() : partitions;
    }

    static KafkaProperties defaults() {
      return new KafkaProperties(null, null, null);
    }
  }

  /**
   * Defines canonical Kafka topic names used by SimpleMatch event flows.
   *
   * @param ordersCommands topic carrying order commands
   * @param ordersValidated topic carrying validated orders
   * @param matchingExecutions topic carrying matching execution results
   * @param marketdataEvents topic carrying market-data events
   * @param auditEvents topic carrying audit events
   */
  public record TopicsProperties(
      String ordersCommands,
      String ordersValidated,
      String matchingExecutions,
      String marketdataEvents,
      String auditEvents) {

    /**
     * Normalizes absent topic names to repository-wide defaults.
     *
     * @param ordersCommands topic carrying order commands
     * @param ordersValidated topic carrying validated orders
     * @param matchingExecutions topic carrying matching execution results
     * @param marketdataEvents topic carrying market-data events
     * @param auditEvents topic carrying audit events
     */
    public TopicsProperties {
      ordersCommands = defaultString(ordersCommands, "orders.commands");
      ordersValidated = defaultString(ordersValidated, "orders.validated");
      matchingExecutions = defaultString(matchingExecutions, "matching.executions");
      marketdataEvents = defaultString(marketdataEvents, "marketdata.events");
      auditEvents = defaultString(auditEvents, "audit.events");
    }

    static TopicsProperties defaults() {
      return new TopicsProperties(null, null, null, null, null);
    }
  }

  /**
   * Defines desired Kafka partition counts used by routing and provisioning inputs.
   *
   * @param ordersCommands partition count for the order-command stream
   * @param ordersValidated partition count for the validated-order stream
   * @param matchingExecutions partition count for the matching-execution stream
   */
  public record PartitionsProperties(
      Integer ordersCommands, Integer ordersValidated, Integer matchingExecutions) {

    /**
     * Normalizes absent partition counts to repository-wide defaults.
     *
     * @param ordersCommands partition count for the order-command stream
     * @param ordersValidated partition count for the validated-order stream
     * @param matchingExecutions partition count for the matching-execution stream
     */
    public PartitionsProperties {
      ordersCommands = defaultPositive(ordersCommands, 15);
      ordersValidated = defaultPositive(ordersValidated, 15);
      matchingExecutions = defaultPositive(matchingExecutions, 15);
    }

    static PartitionsProperties defaults() {
      return new PartitionsProperties(null, null, null);
    }
  }

  /**
   * Holds PostgreSQL connection data for platform services.
   *
   * <p>Credential-bearing DSNs are owned by external secret sources outside local and test
   * environments.
   *
   * @param dsn JDBC URL or supported PostgreSQL DSN
   */
  public record PostgresProperties(String dsn) {

    /**
     * Normalizes an absent DSN to the local SimpleMatch PostgreSQL database.
     *
     * @param dsn JDBC URL or supported PostgreSQL DSN
     */
    public PostgresProperties {
      dsn = defaultString(dsn, "jdbc:postgresql://localhost:5432/simplematch");
    }

    static PostgresProperties defaults() {
      return new PostgresProperties(null);
    }
  }

  /**
   * Holds Redis endpoints used only for rebuildable projections and caches.
   *
   * @param endpoints comma-separated Redis endpoints
   */
  public record RedisProperties(String endpoints) {

    /**
     * Normalizes absent Redis endpoints to the local Redis instance.
     *
     * @param endpoints comma-separated Redis endpoints
     */
    public RedisProperties {
      endpoints = defaultString(endpoints, "localhost:6379");
    }

    static RedisProperties defaults() {
      return new RedisProperties(null);
    }
  }

  /**
   * Groups target addresses for synchronous gRPC service calls.
   *
   * @param targets canonical gRPC service targets
   */
  public record GrpcProperties(GrpcTargetsProperties targets) {

    /**
     * Normalizes an absent target group to the canonical local service targets.
     *
     * @param targets canonical gRPC service targets
     */
    public GrpcProperties {
      targets = targets == null ? GrpcTargetsProperties.defaults() : targets;
    }

    static GrpcProperties defaults() {
      return new GrpcProperties(null);
    }
  }

  /**
   * Defines canonical gRPC targets for synchronously invoked platform services.
   *
   * @param accountService target used to call account-service
   * @param riskService target used to call risk-service
   */
  public record GrpcTargetsProperties(String accountService, String riskService) {

    /**
     * Normalizes absent gRPC targets to Kubernetes-compatible DNS targets used locally.
     *
     * @param accountService target used to call account-service
     * @param riskService target used to call risk-service
     */
    public GrpcTargetsProperties {
      accountService = defaultString(accountService, "dns:///account-service:50051");
      riskService = defaultString(riskService, "dns:///risk-service:50052");
    }

    static GrpcTargetsProperties defaults() {
      return new GrpcTargetsProperties(null, null);
    }
  }

  /**
   * Identifies the immutable routing snapshot consumed by routing components.
   *
   * @param snapshotPath Spring resource location of the routing snapshot
   */
  public record RoutingProperties(String snapshotPath) {

    /**
     * Normalizes an absent routing snapshot path to the bundled classpath resource.
     *
     * @param snapshotPath Spring resource location of the routing snapshot
     */
    public RoutingProperties {
      snapshotPath =
          defaultString(snapshotPath, "classpath:routing/orders-validated.snapshot.json");
    }

    static RoutingProperties defaults() {
      return new RoutingProperties(null);
    }
  }

  /**
   * Groups telemetry export and metrics endpoint settings.
   *
   * @param otel OpenTelemetry exporter settings
   * @param prometheus Prometheus endpoint settings
   */
  public record ObservabilityProperties(OtelProperties otel, PrometheusProperties prometheus) {

    /**
     * Normalizes absent observability groups to local-development defaults.
     *
     * @param otel OpenTelemetry exporter settings
     * @param prometheus Prometheus endpoint settings
     */
    public ObservabilityProperties {
      otel = otel == null ? OtelProperties.defaults() : otel;
      prometheus = prometheus == null ? PrometheusProperties.defaults() : prometheus;
    }

    static ObservabilityProperties defaults() {
      return new ObservabilityProperties(null, null);
    }
  }

  /**
   * Defines the OpenTelemetry Protocol export endpoint.
   *
   * @param exporterOtlpEndpoint OTLP HTTP endpoint receiving exported telemetry
   */
  public record OtelProperties(String exporterOtlpEndpoint) {

    /**
     * Normalizes an absent OTLP endpoint to the local collector endpoint.
     *
     * @param exporterOtlpEndpoint OTLP HTTP endpoint receiving exported telemetry
     */
    public OtelProperties {
      exporterOtlpEndpoint = defaultString(exporterOtlpEndpoint, "http://localhost:4318");
    }

    static OtelProperties defaults() {
      return new OtelProperties(null);
    }
  }

  /**
   * Defines the port exposing Prometheus-compatible metrics.
   *
   * @param port TCP port exposing the metrics endpoint
   */
  public record PrometheusProperties(Integer port) {

    /**
     * Normalizes an absent metrics port to the local exporter port.
     *
     * @param port TCP port exposing the metrics endpoint
     */
    public PrometheusProperties {
      port = defaultPositive(port, 9464);
    }

    static PrometheusProperties defaults() {
      return new PrometheusProperties(null);
    }
  }

  /**
   * Defines stable Taiwan-market defaults shared across services.
   *
   * @param currency ISO 4217 settlement currency code
   * @param timeZone IANA time-zone identifier used for market-local dates and times
   */
  public record MarketProperties(String currency, String timeZone) {

    /**
     * Normalizes absent market settings to Taiwan market defaults.
     *
     * @param currency ISO 4217 settlement currency code
     * @param timeZone IANA time-zone identifier used for market-local dates and times
     */
    public MarketProperties {
      currency = defaultString(currency, "TWD");
      timeZone = defaultString(timeZone, "Asia/Taipei");
    }

    static MarketProperties defaults() {
      return new MarketProperties(null, null);
    }
  }
}
