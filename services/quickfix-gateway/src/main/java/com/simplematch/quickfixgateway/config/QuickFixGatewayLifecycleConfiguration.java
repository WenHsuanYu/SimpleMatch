package com.simplematch.quickfixgateway.config;

import com.simplematch.quickfixgateway.fix.ExecutionSessionResolver;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.FixSessionOwnership;
import com.simplematch.quickfixgateway.fix.InboundFixMessageHandler;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.fix.QuickFixAcceptorLifecycle;
import com.simplematch.quickfixgateway.fix.QuickFixApplicationAdapter;
import com.simplematch.quickfixgateway.health.QuickFixGatewayReadinessHealthIndicator;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupLifecycle;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupRecovery;
import com.simplematch.quickfixgateway.health.QuickFixGatewayStartupState;
import com.simplematch.quickfixgateway.kafka.MatchingExecutionConsumer;
import com.simplematch.quickfixgateway.wal.WalReplayService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the QuickFIX gateway's ingress, readiness, and execution-delivery lifecycle. */
@Configuration
public class QuickFixGatewayLifecycleConfiguration {
  @Bean
  QuickFixGatewayStartupRecovery quickFixGatewayStartupRecovery(
      WalReplayService walReplayService, QuickFixGatewayRuntimeProperties runtimeProperties) {
    if (runtimeProperties.replayEnabled()) {
      return walReplayService::replayAll;
    }
    return () -> 0;
  }

  @Bean
  QuickFixGatewayStartupState quickFixGatewayStartupState() {
    return new QuickFixGatewayStartupState();
  }

  @Bean
  FixSessionOwnership fixSessionOwnership() {
    return new FixSessionOwnership();
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
      QuickFixGatewayRuntimeProperties runtimeProperties) {
    return new QuickFixGatewayReadinessHealthIndicator(
        startupState,
        runtimeProperties.acceptorEnabled(),
        () -> {
          final QuickFixAcceptorLifecycle lifecycle = acceptorLifecycleProvider.getIfAvailable();
          return lifecycle != null && lifecycle.isRunning();
        });
  }

  @Bean
  QuickFixApplicationAdapter quickFixApplicationAdapter(
      InboundFixMessageHandler inboundFixMessageHandler,
      FixSessionOwnership sessionOwnership,
      QuickFixGatewayRuntime runtime) {
    return new QuickFixApplicationAdapter(
        inboundFixMessageHandler, sessionOwnership, runtime.ownerId());
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
}
