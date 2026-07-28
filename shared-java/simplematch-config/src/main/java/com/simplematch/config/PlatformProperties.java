package com.simplematch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared platform settings bound from the Spring {@code Environment}.
 *
 * <p>The defaults are deliberately local-only and contain no credentials. Staging and production
 * credentials are validated as Kubernetes Secret-owned values at startup.
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

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer defaultPositive(Integer value, int fallback) {
        return value == null ? Integer.valueOf(fallback) : value;
    }

    /**
     * Kafka connectivity, topic, and partition settings shared by event-producing services.
     */
    public record KafkaProperties(String brokers, TopicsProperties topics, PartitionsProperties partitions) {
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
     * Canonical Kafka topic names.
     */
    public record TopicsProperties(
            String ordersCommands,
            String ordersValidated,
            String matchingExecutions,
            String marketdataEvents,
            String auditEvents) {
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
     * Desired Kafka partition counts used by routing and provisioning inputs.
     */
    public record PartitionsProperties(
            Integer ordersCommands, Integer ordersValidated, Integer matchingExecutions) {
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
     * PostgreSQL connection data; a credential-bearing DSN is Secret-owned outside local and test.
     */
    public record PostgresProperties(String dsn) {
        public PostgresProperties {
            dsn = defaultString(dsn, "jdbc:postgresql://localhost:5432/simplematch");
        }

        static PostgresProperties defaults() {
            return new PostgresProperties(null);
        }
    }

    /**
     * Redis endpoint list for rebuildable projections.
     */
    public record RedisProperties(String endpoints) {
        public RedisProperties {
            endpoints = defaultString(endpoints, "localhost:6379");
        }

        static RedisProperties defaults() {
            return new RedisProperties(null);
        }
    }

    /**
     * gRPC endpoint targets for synchronous service calls.
     */
    public record GrpcProperties(GrpcTargetsProperties targets) {
        public GrpcProperties {
            targets = targets == null ? GrpcTargetsProperties.defaults() : targets;
        }

        static GrpcProperties defaults() {
            return new GrpcProperties(null);
        }
    }

    /**
     * Canonical gRPC service targets.
     */
    public record GrpcTargetsProperties(String accountService, String riskService) {
        public GrpcTargetsProperties {
            accountService = defaultString(accountService, "dns:///account-service:50051");
            riskService = defaultString(riskService, "dns:///risk-service:50052");
        }

        static GrpcTargetsProperties defaults() {
            return new GrpcTargetsProperties(null, null);
        }
    }

    /**
     * Immutable routing snapshot input.
     */
    public record RoutingProperties(String snapshotPath) {
        public RoutingProperties {
            snapshotPath = defaultString(snapshotPath, "classpath:routing/orders-validated.snapshot.json");
        }

        static RoutingProperties defaults() {
            return new RoutingProperties(null);
        }
    }

    /**
     * Observability exporter and metrics port settings.
     */
    public record ObservabilityProperties(OtelProperties otel, PrometheusProperties prometheus) {
        public ObservabilityProperties {
            otel = otel == null ? OtelProperties.defaults() : otel;
            prometheus = prometheus == null ? PrometheusProperties.defaults() : prometheus;
        }

        static ObservabilityProperties defaults() {
            return new ObservabilityProperties(null, null);
        }
    }

    /**
     * OpenTelemetry export endpoint.
     */
    public record OtelProperties(String exporterOtlpEndpoint) {
        public OtelProperties {
            exporterOtlpEndpoint = defaultString(exporterOtlpEndpoint, "http://localhost:4318");
        }

        static OtelProperties defaults() {
            return new OtelProperties(null);
        }
    }

    /**
     * Prometheus endpoint port.
     */
    public record PrometheusProperties(Integer port) {
        public PrometheusProperties {
            port = defaultPositive(port, 9464);
        }

        static PrometheusProperties defaults() {
            return new PrometheusProperties(null);
        }
    }

    /**
     * Taiwan-market defaults that are stable across services.
     */
    public record MarketProperties(String currency, String timeZone) {
        public MarketProperties {
            currency = defaultString(currency, "TWD");
            timeZone = defaultString(timeZone, "Asia/Taipei");
        }

        static MarketProperties defaults() {
            return new MarketProperties(null, null);
        }
    }
}
