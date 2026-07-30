package com.simplematch.quickfixgateway.config;

import com.simplematch.config.PlatformProperties;
import com.simplematch.quickfixgateway.fix.ExecutionSessionResolver;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.InboundFixMessageHandler;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.fix.QuickFixAcceptorLifecycle;
import com.simplematch.quickfixgateway.fix.QuickFixApplicationAdapter;
import com.simplematch.quickfixgateway.fix.QuickFixSessionMessageSender;
import com.simplematch.quickfixgateway.health.QuickFixGatewayReadinessHealthIndicator;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupLifecycle;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupRecovery;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupState;
import com.simplematch.quickfixgateway.kafka.KafkaOrdersCommandPublisher;
import com.simplematch.quickfixgateway.kafka.MatchingExecutionConsumer;
import com.simplematch.quickfixgateway.kafka.NoopOrdersCommandPublisher;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.GrpcRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.ResilientRiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalReplayService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;

/** Wires the QuickFIX gateway's runtime, messaging, and recovery components. */
@Configuration
@EnableKafka
@EnableConfigurationProperties(QuickFixGatewayProperties.class)
@SuppressWarnings("PMD.TooManyMethods")
// Spring wiring seam; bean methods remain explicit for operational dependencies.
public class QuickFixGatewayConfiguration {
  @Bean
  SmartInitializingSingleton quickFixGatewayPropertiesValidation(
      QuickFixGatewayProperties gatewayProperties) {
    return () -> QuickFixGatewayPropertiesValidator.validate(gatewayProperties);
  }

  @Bean
  QuickFixGatewayRuntime quickFixGatewayRuntime(
      PlatformProperties platformProperties, QuickFixGatewayProperties gatewayProperties)
      throws IOException {
    final Path quickfixConfigPath = resolve(gatewayProperties.quickfixConfigPath());
    if (!Files.exists(quickfixConfigPath)) {
      throw new IllegalStateException("QuickFIX/J config not found: " + quickfixConfigPath);
    }

    final Path walPath = resolve(gatewayProperties.walPath());
    final Path walParent = walPath.getParent();
    if (walParent != null) {
      Files.createDirectories(walParent);
    }

    return new QuickFixGatewayRuntime(
        platformProperties.environment(), quickfixConfigPath, walPath, gatewayProperties.ownerId());
  }

  @Bean
  Clock quickFixGatewayClock() {
    return Clock.systemUTC();
  }

  @Bean
  WalAppender walAppender(QuickFixGatewayRuntime runtime) {
    return new WalAppender(runtime.walPath(), StandardCharsets.UTF_8);
  }

  @Bean
  FixSessionMessageSender fixSessionMessageSender() {
    return new QuickFixSessionMessageSender();
  }

  @Bean
  OrderSessionRegistry orderSessionRegistry() {
    return new OrderSessionRegistry();
  }

  @Bean
  FixMessageMapper fixMessageMapper(Clock clock) {
    return new FixMessageMapper(clock);
  }

  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel riskServiceChannel(PlatformProperties platformProperties) {
    return ManagedChannelBuilder.forTarget(platformProperties.grpc().targets().riskService())
        .usePlaintext()
        .build();
  }

  @Bean
  RiskSubmissionClient riskSubmissionClient(
      ManagedChannel riskServiceChannel,
      Clock quickFixGatewayClock,
      QuickFixGatewayProperties gatewayProperties) {
    final QuickFixGatewayProperties.RiskClientProperties riskClient =
        gatewayProperties.riskClient();
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
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.compatibility-publish-enabled",
      havingValue = "true")
  OrdersCommandPublisher ordersCommandPublisher(
      KafkaTemplate<String, byte[]> kafkaTemplate, PlatformProperties platformProperties) {
    return new KafkaOrdersCommandPublisher(
        kafkaTemplate, platformProperties.kafka().topics().ordersCommands());
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
      WalAppender walAppender, OrdersCommandPublisher ordersCommandPublisher) {
    return new WalReplayService(walAppender, ordersCommandPublisher);
  }

  @Bean
  QuickFixGatewayStartupRecovery quickFixGatewayStartupRecovery(
      WalReplayService walReplayService, QuickFixGatewayProperties gatewayProperties) {
    if (gatewayProperties.compatibilityPublishEnabled() && gatewayProperties.replayEnabled()) {
      return walReplayService::replayAll;
    }
    return () -> 0;
  }

  @Bean
  QuickFixGatewayStartupState quickFixGatewayStartupState() {
    return new QuickFixGatewayStartupState();
  }

  @Bean
  QuickFixGatewayStartupLifecycle quickFixGatewayStartupLifecycle(
      QuickFixGatewayRuntime runtime,
      QuickFixGatewayStartupRecovery startupRecovery,
      QuickFixGatewayStartupState startupState) {
    return new QuickFixGatewayStartupLifecycle(runtime.ownerId(), startupRecovery, startupState);
  }

  @Bean("quickfixGatewayReadyHealthIndicator")
  QuickFixGatewayReadinessHealthIndicator quickfixGatewayReadyHealthIndicator(
      QuickFixGatewayStartupState startupState,
      ObjectProvider<QuickFixAcceptorLifecycle> acceptorLifecycleProvider,
      QuickFixGatewayProperties gatewayProperties) {
    return new QuickFixGatewayReadinessHealthIndicator(
        startupState,
        gatewayProperties.acceptorEnabled(),
        () -> {
          final QuickFixAcceptorLifecycle lifecycle = acceptorLifecycleProvider.getIfAvailable();
          return lifecycle != null && lifecycle.isRunning();
        });
  }

  @Bean
  InboundFixMessageHandler inboundFixMessageHandler(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      Clock quickFixGatewayClock) {
    return new InboundFixMessageHandler(
        walAppender,
        ordersCommandPublisher,
        riskSubmissionClient,
        fixSessionMessageSender,
        orderSessionRegistry,
        fixMessageMapper,
        quickFixGatewayClock);
  }

  @Bean
  QuickFixApplicationAdapter quickFixApplicationAdapter(
      InboundFixMessageHandler inboundFixMessageHandler) {
    return new QuickFixApplicationAdapter(inboundFixMessageHandler);
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.acceptor-enabled",
      havingValue = "true",
      matchIfMissing = true)
  QuickFixAcceptorLifecycle quickFixAcceptorLifecycle(
      QuickFixApplicationAdapter application, QuickFixGatewayRuntime runtime) {
    return new QuickFixAcceptorLifecycle(application, runtime);
  }

  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.data-plane-enabled",
      havingValue = "true",
      matchIfMissing = true)
  MatchingExecutionConsumer matchingExecutionConsumer(
      ExecutionSessionResolver executionSessionResolver,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      FixSessionMessageSender fixSessionMessageSender) {
    return new MatchingExecutionConsumer(
        executionSessionResolver, orderSessionRegistry, fixMessageMapper, fixSessionMessageSender);
  }

  private Path resolve(String rawPath) {
    final Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      return path.normalize();
    }

    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("settings.gradle.kts"))
          || Files.exists(current.resolve(".git"))) {
        return current.resolve(path).normalize();
      }
      current = current.getParent();
    }

    return Path.of("").toAbsolutePath().resolve(path).normalize();
  }
}
