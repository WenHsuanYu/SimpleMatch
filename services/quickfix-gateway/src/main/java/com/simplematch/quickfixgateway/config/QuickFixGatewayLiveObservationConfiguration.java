package com.simplematch.quickfixgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.KafkaProperties;
import com.simplematch.config.delivery.CriticalConsumerOperationalStatusProvider;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import com.simplematch.quickfixgateway.operations.DeadlineTradingSystemObservationCollector;
import com.simplematch.quickfixgateway.operations.GatewayLiveObservationReporter;
import com.simplematch.quickfixgateway.operations.GatewayOperationalController;
import com.simplematch.quickfixgateway.operations.TradingSystemObservationCollector;
import com.simplematch.quickfixgateway.operations.live.AdminKafkaAdmissionClient;
import com.simplematch.quickfixgateway.operations.live.GatewayLiveStatusClient;
import com.simplematch.quickfixgateway.operations.live.JdkStatusDocumentClient;
import com.simplematch.quickfixgateway.operations.live.KafkaAdmissionClient;
import com.simplematch.quickfixgateway.operations.live.LiveTradingSystemObservationCollector;
import com.simplematch.quickfixgateway.operations.live.StatusDocumentClient;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires production live observation without exposing HTTP or Kafka types to domain policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayLiveObservationProperties.class)
@ConditionalOnProperty(
    name = "simplematch.quickfix-gateway.live-observation.enabled",
    havingValue = "true")
public class QuickFixGatewayLiveObservationConfiguration {
  /** Creates the bounded internal HTTP transport. */
  @Bean
  HttpClient gatewayLiveObservationHttpClient(GatewayLiveObservationProperties properties) {
    return HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
  }

  /** Creates the fail-closed JSON document client. */
  @Bean
  StatusDocumentClient gatewayStatusDocumentClient(
      HttpClient gatewayLiveObservationHttpClient,
      ObjectMapper objectMapper,
      GatewayLiveObservationProperties properties) {
    return new JdkStatusDocumentClient(
        gatewayLiveObservationHttpClient, objectMapper, properties.requestTimeout());
  }

  /** Creates task-per-request concurrency bounded by the collector's total deadline. */
  @Bean(destroyMethod = "close")
  ExecutorService gatewayLiveObservationExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Creates the internal HTTP normalization adapter. */
  @Bean
  GatewayLiveStatusClient gatewayLiveStatusClient(
      StatusDocumentClient documents,
      GatewayLiveObservationProperties properties,
      CriticalConsumerOperationalStatusProvider quickFixCriticalConsumerOperationalStatusProvider,
      QuickFixFinalMatchingEventStatus quickFixFinalMatchingEventStatus,
      ExecutorService gatewayLiveObservationExecutor) {
    return new GatewayLiveStatusClient(
        documents,
        properties.endpoints(),
        () ->
            quickFixCriticalConsumerOperationalStatusProvider.current(
                quickFixFinalMatchingEventStatus.committedOffsets(),
                quickFixFinalMatchingEventStatus.oldestUnprocessedAgeMillis()),
        gatewayLiveObservationExecutor);
  }

  /** Creates a dedicated Kafka Admin connection for admission observation. */
  @Bean(destroyMethod = "close")
  Admin gatewayAdmissionKafkaAdmin(
      org.springframework.boot.kafka.autoconfigure.KafkaProperties bootKafka) {
    final var properties = new HashMap<>(bootKafka.buildAdminProperties());
    properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "quickfix-gateway-admission-observer");
    return Admin.create(properties);
  }

  /** Creates the Kafka topology and durable-progress adapter. */
  @Bean
  KafkaAdmissionClient kafkaAdmissionClient(
      Admin gatewayAdmissionKafkaAdmin,
      KafkaProperties kafkaProperties,
      GatewayLiveObservationProperties properties,
      Clock quickFixGatewayClock) {
    return new AdminKafkaAdmissionClient(
        gatewayAdmissionKafkaAdmin,
        kafkaProperties.topics().matchingCommands(),
        kafkaProperties.topics().matchingEvents(),
        properties.consumerGroups(),
        properties.requestTimeout(),
        quickFixGatewayClock);
  }

  /** Creates the all-or-nothing production observation collector. */
  @Bean
  TradingSystemObservationCollector tradingSystemObservationCollector(
      GatewayLiveStatusClient statusClient,
      KafkaAdmissionClient kafkaClient,
      GatewayLiveObservationProperties liveProperties,
      QuickFixGatewayOperationsProperties operationsProperties,
      ExecutorService gatewayLiveObservationExecutor) {
    if (liveProperties
            .collectionTimeout()
            .compareTo(operationsProperties.staleStatusAfter())
        >= 0) {
      throw new IllegalArgumentException(
          "live observation collection timeout must be shorter than stale-status-after");
    }
    return new DeadlineTradingSystemObservationCollector(
        new LiveTradingSystemObservationCollector(
            statusClient, kafkaClient, gatewayLiveObservationExecutor),
        gatewayLiveObservationExecutor,
        liveProperties.collectionTimeout());
  }

  /** Publishes complete observations on the bounded scheduler. */
  @Bean
  GatewayLiveObservationReporter gatewayLiveObservationReporter(
      TradingSystemObservationCollector collector, GatewayOperationalController controller) {
    return new GatewayLiveObservationReporter(collector, controller);
  }
}
