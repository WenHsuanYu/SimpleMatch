package com.simplematch.quickfixgateway.config;

import com.simplematch.quickfixgateway.fix.GatewayAdmissionGate;
import com.simplematch.quickfixgateway.operations.GatewayOperationAuditStore;
import com.simplematch.quickfixgateway.operations.GatewayOperationalCommandHandler;
import com.simplematch.quickfixgateway.operations.GatewayOperationalController;
import com.simplematch.quickfixgateway.operations.GatewayOperationalMonitor;
import com.simplematch.quickfixgateway.operations.GatewayOperationalPolicy;
import com.simplematch.quickfixgateway.operations.TradingSessionClosePort;
import com.simplematch.quickfixgateway.operations.TradingSystemStatusEvaluator;
import com.simplematch.quickfixgateway.store.JdbcGatewayOperationAuditStore;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the single Gateway's technology-neutral operational admission authority. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class QuickFixGatewayOperationsConfiguration {
  /** Creates the Gateway-owned durable audit adapter for admission operations. */
  @Bean
  GatewayOperationAuditStore gatewayOperationAuditStore(JdbcTemplate jdbcTemplate) {
    return new JdbcGatewayOperationAuditStore(jdbcTemplate);
  }

  /** Creates the immutable policy used by the domain evaluator and controller. */
  @Bean
  GatewayOperationalPolicy gatewayOperationalPolicy(
      QuickFixGatewayOperationsProperties properties) {
    return new GatewayOperationalPolicy(
        properties.requiredConsecutiveOpenEligibleChecks(),
        properties.staleStatusAfter(),
        properties.warningOldestEventAfter(),
        properties.pauseOldestEventAfter(),
        ZoneId.of(properties.sessionZone()),
        LocalTime.parse(properties.sessionCloseTime()),
        properties.automaticCloseEnabled());
  }

  /** Creates the pure readiness evaluator with the fixed 15-partition Phase 1 topology. */
  @Bean
  TradingSystemStatusEvaluator tradingSystemStatusEvaluator(
      QuickFixGatewayOperationsProperties properties) {
    return new TradingSystemStatusEvaluator(
        15,
        properties.staleStatusAfter(),
        properties.warningOldestEventAfter(),
        properties.pauseOldestEventAfter());
  }

  /** Creates the single Gateway application controller for all trading-day admission commands. */
  @Bean
  GatewayOperationalController gatewayOperationalController(
      GatewayAdmissionGate admissionGate,
      TradingSystemStatusEvaluator statusEvaluator,
      GatewayOperationalPolicy policy,
      GatewayOperationAuditStore auditStore,
      TradingSessionClosePort tradingSessionClosePort,
      Clock quickFixGatewayClock) {
    return new GatewayOperationalController(
        admissionGate,
        statusEvaluator,
        policy,
        auditStore,
        tradingSessionClosePort,
        quickFixGatewayClock);
  }

  /** Creates the sole transport-neutral command boundary for Gateway admission operations. */
  @Bean
  GatewayOperationalCommandHandler gatewayOperationalCommandHandler(
      GatewayOperationalController controller) {
    return new GatewayOperationalCommandHandler(controller);
  }

  /** Creates the scheduled adapter for stale-status protection and automatic session close. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.quickfix-gateway.operations.monitor-enabled",
      havingValue = "true",
      matchIfMissing = true)
  GatewayOperationalMonitor gatewayOperationalMonitor(GatewayOperationalController controller) {
    return new GatewayOperationalMonitor(controller);
  }
}
