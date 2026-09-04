package com.simplematch.quickfixgateway.config;

import com.simplematch.config.GrpcProperties;
import com.simplematch.contracts.v2.VenueMic;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.operations.TradingSessionClosePort;
import com.simplematch.quickfixgateway.risk.GrpcRiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.GrpcRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.GrpcTradingSessionCloseClient;
import com.simplematch.quickfixgateway.risk.ResilientRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskCommandMapper;
import com.simplematch.quickfixgateway.risk.RiskCommandSubmitter;
import com.simplematch.quickfixgateway.risk.RiskOrderIdentityDeriver;
import com.simplematch.quickfixgateway.risk.RiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import com.simplematch.quickfixgateway.wal.WalReplayService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires external Risk adapters and state-aware WAL recovery for the QuickFIX gateway. */
@Configuration
public class QuickFixGatewayIntegrationConfiguration {
  @Bean
  FixMessageMapper fixMessageMapper() {
    return new FixMessageMapper();
  }

  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel riskServiceChannel(GrpcProperties grpcProperties) {
    final String target = grpcProperties.targets().riskService();
    final GrpcProperties.SecurityProperties security = grpcProperties.security();
    if (!security.tlsEnabled()) {
      return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    }
    try {
      return NettyChannelBuilder.forTarget(target)
          .sslContext(
              GrpcSslContexts.forClient()
                  .trustManager(new File(security.trustCertificatePath()))
                  .keyManager(
                      new File(security.certificatePath()), new File(security.privateKeyPath()))
                  .build())
          .useTransportSecurity()
          .build();
    } catch (IOException failure) {
      throw new IllegalStateException("failed to configure risk-service gRPC TLS", failure);
    }
  }

  @Bean
  RiskOrderIdentityDeriver riskOrderIdentityDeriver(
      QuickFixGatewayIngressProperties ingressProperties) {
    return new RiskOrderIdentityDeriver(ingressProperties.tradingDay());
  }

  @Bean
  RiskCommandMapper riskCommandMapper(
      QuickFixGatewayIngressProperties ingressProperties,
      RiskOrderIdentityDeriver orderIdentityDeriver) {
    return new RiskCommandMapper(
        VenueMic.parse(ingressProperties.venueMic()), orderIdentityDeriver);
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
  RiskCommandSubmitter riskCommandSubmitter(
      RiskCommandMapper commandMapper, RiskSubmissionClient submissionClient) {
    return new RiskCommandSubmitter(commandMapper, submissionClient);
  }

  @Bean
  RiskReconciliationClient riskReconciliationClient(
      ManagedChannel riskServiceChannel, QuickFixGatewayRiskClientProperties riskClient) {
    return new GrpcRiskReconciliationClient(riskServiceChannel, riskClient.deadlineMillis());
  }

  /** Reuses the Risk channel for idempotent trading-session Close Barrier publication. */
  @Bean
  TradingSessionClosePort tradingSessionClosePort(
      ManagedChannel riskServiceChannel, QuickFixGatewayRiskClientProperties riskClient) {
    return new GrpcTradingSessionCloseClient(riskServiceChannel, riskClient.deadlineMillis());
  }

  @Bean
  WalReplayService walReplayService(
      WalAppender walAppender,
      WalRecoveryJournal recoveryJournal,
      RiskCommandSubmitter riskCommandSubmitter,
      RiskReconciliationClient reconciliationClient,
      OrderSessionRegistry orderSessionRegistry) {
    return new WalReplayService(
        walAppender,
        recoveryJournal,
        riskCommandSubmitter,
        reconciliationClient,
        orderSessionRegistry);
  }
}
