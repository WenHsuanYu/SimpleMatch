package com.simplematch.quickfixgateway.config;

import com.simplematch.config.GrpcProperties;
import com.simplematch.config.KafkaProperties;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.kafka.KafkaOrdersCommandPublisher;
import com.simplematch.quickfixgateway.kafka.NoopOrdersCommandPublisher;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.GrpcRiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.GrpcRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.ResilientRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalReplayService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Wires external risk and compatibility-publication adapters for the QuickFIX gateway. */
@Configuration
public class QuickFixGatewayIntegrationConfiguration {
  @Bean
  FixMessageMapper fixMessageMapper(Clock clock) {
    return new FixMessageMapper(clock);
  }

  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel riskServiceChannel(GrpcProperties grpcProperties) {
    return ManagedChannelBuilder.forTarget(grpcProperties.targets().riskService())
        .usePlaintext()
        .build();
  }

  @Bean
  RiskSubmissionClient riskSubmissionClient(
      ManagedChannel riskServiceChannel,
      Clock quickFixGatewayClock,
      QuickFixGatewayRiskClientProperties riskClient) {
    final RiskSubmissionClient delegate =
        new GrpcRiskSubmissionClient(riskServiceChannel, riskClient.deadlineMillis());
    return new ResilientRiskSubmissionClient(
        delegate,
        riskClient.retry().maxAttempts(),
        riskClient.retry().backoffMillis(),
        riskClient.breaker().consecutiveFailures(),
        riskClient.breaker().openDurationMillis(),
        quickFixGatewayClock);
  }

  @Bean
  RiskReconciliationClient riskReconciliationClient(
      ManagedChannel riskServiceChannel, QuickFixGatewayRiskClientProperties riskClient) {
    return new GrpcRiskReconciliationClient(riskServiceChannel, riskClient.deadlineMillis());
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.compatibility-publish-enabled",
      havingValue = "true")
  OrdersCommandPublisher ordersCommandPublisher(
      KafkaTemplate<String, byte[]> kafkaTemplate, KafkaProperties kafkaProperties) {
    return new KafkaOrdersCommandPublisher(
        kafkaTemplate, kafkaProperties.topics().ordersCommands());
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.compatibility-publish-enabled",
      havingValue = "false",
      matchIfMissing = true)
  OrdersCommandPublisher noopOrdersCommandPublisher() {
    return new NoopOrdersCommandPublisher();
  }

  @Bean
  WalReplayService walReplayService(
      WalAppender walAppender, RiskSubmissionClient riskSubmissionClient) {
    return new WalReplayService(walAppender, riskSubmissionClient);
  }
}
