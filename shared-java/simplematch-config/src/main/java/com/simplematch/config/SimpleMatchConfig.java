package com.simplematch.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.errorprone.annotations.InlineMe;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@ConfigurationProperties(prefix = "simplematch")
public final class SimpleMatchConfig {
  private String env = "dev";
  private Kafka kafka = new Kafka();
  private Postgres postgres = new Postgres();
  private Redis redis = new Redis();
  private Grpc grpc = new Grpc();
  private Routing routing = new Routing();
  private Observability observability = new Observability();
  private QuickFixGateway quickfixGateway = new QuickFixGateway();

  public String getEnv() {
    return env;
  }

  public void setEnv(String env) {
    if (env != null && !env.isBlank()) {
      this.env = env;
    }
  }

  public Kafka getKafka() {
    return kafka;
  }

  public void setKafka(Kafka kafka) {
    if (kafka != null) {
      this.kafka = kafka;
    }
  }

  public Postgres getPostgres() {
    return postgres;
  }

  public void setPostgres(Postgres postgres) {
    if (postgres != null) {
      this.postgres = postgres;
    }
  }

  public Redis getRedis() {
    return redis;
  }

  public void setRedis(Redis redis) {
    if (redis != null) {
      this.redis = redis;
    }
  }

  public Grpc getGrpc() {
    return grpc;
  }

  public void setGrpc(Grpc grpc) {
    if (grpc != null) {
      this.grpc = grpc;
    }
  }

  public Routing getRouting() {
    return routing;
  }

  public void setRouting(Routing routing) {
    if (routing != null) {
      this.routing = routing;
    }
  }

  public Observability getObservability() {
    return observability;
  }

  public void setObservability(Observability observability) {
    if (observability != null) {
      this.observability = observability;
    }
  }

  public QuickFixGateway getQuickfixGateway() {
    return quickfixGateway;
  }

  public void setQuickfixGateway(QuickFixGateway quickfixGateway) {
    if (quickfixGateway != null) {
      this.quickfixGateway = quickfixGateway;
    }
  }

  @Deprecated
  public QuickFixGateway getFixGateway() {
    return quickfixGateway;
  }

  @Deprecated
  @InlineMe(replacement = "this.setQuickfixGateway(fixGateway)")
  public void setFixGateway(QuickFixGateway fixGateway) {
    setQuickfixGateway(fixGateway);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Kafka {
    private String brokers = "localhost:9092";
    private Topics topics = new Topics();
    private Partitions partitions = new Partitions();

    public String getBrokers() {
      return brokers;
    }

    public void setBrokers(String brokers) {
      if (brokers != null && !brokers.isBlank()) {
        this.brokers = brokers;
      }
    }

    public Topics getTopics() {
      return topics;
    }

    public void setTopics(Topics topics) {
      if (topics != null) {
        this.topics = topics;
      }
    }

    public Partitions getPartitions() {
      return partitions;
    }

    public void setPartitions(Partitions partitions) {
      if (partitions != null) {
        this.partitions = partitions;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Topics {
    private String ordersCommands = "orders.commands";
    private String ordersValidated = "orders.validated";
    private String matchingExecutions = "matching.executions";
    private String marketdataEvents = "marketdata.events";
    private String auditEvents = "audit.events";

    public String getOrdersCommands() {
      return ordersCommands;
    }

    public void setOrdersCommands(String ordersCommands) {
      if (ordersCommands != null && !ordersCommands.isBlank()) {
        this.ordersCommands = ordersCommands;
      }
    }

    public String getOrdersValidated() {
      return ordersValidated;
    }

    public void setOrdersValidated(String ordersValidated) {
      if (ordersValidated != null && !ordersValidated.isBlank()) {
        this.ordersValidated = ordersValidated;
      }
    }

    public String getMatchingExecutions() {
      return matchingExecutions;
    }

    public void setMatchingExecutions(String matchingExecutions) {
      if (matchingExecutions != null && !matchingExecutions.isBlank()) {
        this.matchingExecutions = matchingExecutions;
      }
    }

    public String getMarketdataEvents() {
      return marketdataEvents;
    }

    public void setMarketdataEvents(String marketdataEvents) {
      if (marketdataEvents != null && !marketdataEvents.isBlank()) {
        this.marketdataEvents = marketdataEvents;
      }
    }

    public String getAuditEvents() {
      return auditEvents;
    }

    public void setAuditEvents(String auditEvents) {
      if (auditEvents != null && !auditEvents.isBlank()) {
        this.auditEvents = auditEvents;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Partitions {
    private Integer ordersCommands = 15;
    private Integer ordersValidated = 15;
    private Integer matchingExecutions = 15;

    public Integer getOrdersCommands() {
      return ordersCommands;
    }

    public void setOrdersCommands(Integer ordersCommands) {
      if (ordersCommands != null) {
        this.ordersCommands = ordersCommands;
      }
    }

    public Integer getOrdersValidated() {
      return ordersValidated;
    }

    public void setOrdersValidated(Integer ordersValidated) {
      if (ordersValidated != null) {
        this.ordersValidated = ordersValidated;
      }
    }

    public Integer getMatchingExecutions() {
      return matchingExecutions;
    }

    public void setMatchingExecutions(Integer matchingExecutions) {
      if (matchingExecutions != null) {
        this.matchingExecutions = matchingExecutions;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Postgres {
    private String dsn = "postgresql://simplematch:simplematch@localhost:5432/simplematch";

    public String getDsn() {
      return dsn;
    }

    public void setDsn(String dsn) {
      if (dsn != null && !dsn.isBlank()) {
        this.dsn = dsn;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Redis {
    private List<String> endpoints = new ArrayList<>(List.of("localhost:6379"));

    public List<String> getEndpoints() {
      return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
      if (endpoints != null && !endpoints.isEmpty()) {
        this.endpoints = new ArrayList<>(endpoints);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Grpc {
    private Targets targets = new Targets();

    public Targets getTargets() {
      return targets;
    }

    public void setTargets(Targets targets) {
      if (targets != null) {
        this.targets = targets;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Routing {
    private String snapshotPath = "classpath:routing/orders-validated.snapshot.json";

    public String getSnapshotPath() {
      return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
      if (snapshotPath != null && !snapshotPath.isBlank()) {
        this.snapshotPath = snapshotPath;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Targets {
    private String accountService = "dns:///account-service:50051";
    private String riskService = "dns:///risk-service:50052";

    public String getAccountService() {
      return accountService;
    }

    public void setAccountService(String accountService) {
      if (accountService != null && !accountService.isBlank()) {
        this.accountService = accountService;
      }
    }

    public String getRiskService() {
      return riskService;
    }

    public void setRiskService(String riskService) {
      if (riskService != null && !riskService.isBlank()) {
        this.riskService = riskService;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Observability {
    private OTel otel = new OTel();
    private Prometheus prometheus = new Prometheus();

    public OTel getOtel() {
      return otel;
    }

    public void setOtel(OTel otel) {
      if (otel != null) {
        this.otel = otel;
      }
    }

    public Prometheus getPrometheus() {
      return prometheus;
    }

    public void setPrometheus(Prometheus prometheus) {
      if (prometheus != null) {
        this.prometheus = prometheus;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class OTel {
    private String exporterOtlpEndpoint = "http://localhost:4318";

    public String getExporterOtlpEndpoint() {
      return exporterOtlpEndpoint;
    }

    public void setExporterOtlpEndpoint(String exporterOtlpEndpoint) {
      if (exporterOtlpEndpoint != null && !exporterOtlpEndpoint.isBlank()) {
        this.exporterOtlpEndpoint = exporterOtlpEndpoint;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Prometheus {
    private Integer port = 9464;

    public Integer getPort() {
      return port;
    }

    public void setPort(Integer port) {
      if (port != null) {
        this.port = port;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class QuickFixGateway {
    private String quickfixConfigPath = "config/quickfix/acceptor.cfg";
    private String walPath = "data/quickfix/wal/inbound.wal";
    private String ownerId = "quickfix-gateway-0";
    private RiskClient riskClient = new RiskClient();

    public String getQuickfixConfigPath() {
      return quickfixConfigPath;
    }

    public void setQuickfixConfigPath(String quickfixConfigPath) {
      if (quickfixConfigPath != null && !quickfixConfigPath.isBlank()) {
        this.quickfixConfigPath = quickfixConfigPath;
      }
    }

    public String getWalPath() {
      return walPath;
    }

    public void setWalPath(String walPath) {
      if (walPath != null && !walPath.isBlank()) {
        this.walPath = walPath;
      }
    }

    public String getOwnerId() {
      return ownerId;
    }

    public void setOwnerId(String ownerId) {
      if (ownerId != null && !ownerId.isBlank()) {
        this.ownerId = ownerId;
      }
    }

    public RiskClient getRiskClient() {
      return riskClient;
    }

    public void setRiskClient(RiskClient riskClient) {
      if (riskClient != null) {
        this.riskClient = riskClient;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class RiskClient {
    private Integer deadlineMillis = 1_500;
    private Retry retry = new Retry();
    private Breaker breaker = new Breaker();

    public Integer getDeadlineMillis() {
      return deadlineMillis;
    }

    public void setDeadlineMillis(Integer deadlineMillis) {
      if (deadlineMillis != null && deadlineMillis > 0) {
        this.deadlineMillis = deadlineMillis;
      }
    }

    public Retry getRetry() {
      return retry;
    }

    public void setRetry(Retry retry) {
      if (retry != null) {
        this.retry = retry;
      }
    }

    public Breaker getBreaker() {
      return breaker;
    }

    public void setBreaker(Breaker breaker) {
      if (breaker != null) {
        this.breaker = breaker;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Retry {
    private Integer maxAttempts = 2;
    private Integer backoffMillis = 50;

    public Integer getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
      if (maxAttempts != null && maxAttempts > 0) {
        this.maxAttempts = maxAttempts;
      }
    }

    public Integer getBackoffMillis() {
      return backoffMillis;
    }

    public void setBackoffMillis(Integer backoffMillis) {
      if (backoffMillis != null && backoffMillis >= 0) {
        this.backoffMillis = backoffMillis;
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Breaker {
    private Integer consecutiveFailures = 3;
    private Integer openDurationMillis = 1_000;

    public Integer getConsecutiveFailures() {
      return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
      if (consecutiveFailures != null && consecutiveFailures > 0) {
        this.consecutiveFailures = consecutiveFailures;
      }
    }

    public Integer getOpenDurationMillis() {
      return openDurationMillis;
    }

    public void setOpenDurationMillis(Integer openDurationMillis) {
      if (openDurationMillis != null && openDurationMillis > 0) {
        this.openDurationMillis = openDurationMillis;
      }
    }
  }

}