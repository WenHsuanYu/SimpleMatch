package com.simplematch.riskservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.KafkaProperties;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.riskservice.admission.AdmissionRoutingPolicyResolver;
import com.simplematch.riskservice.admission.DailyArtifactAdmissionRoutingResolver;
import com.simplematch.riskservice.admission.MatchingBarrierOutboxFactory;
import com.simplematch.riskservice.admission.TradingSessionBarrierService;
import com.simplematch.riskservice.outbox.OutboxRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires Risk Admission to its one verified final daily artifact with no Market Reference consumer.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketReferenceArtifactProperties.class)
public class RiskDailyArtifactConfiguration {
  /**
   * Loads and verifies the exact mounted artifact bytes before the application can report ready.
   */
  @Bean
  VerifiedMarketReferenceArtifact verifiedMarketReferenceArtifact(
      MarketReferenceArtifactProperties properties,
      ResourceLoader resourceLoader,
      ObjectMapper objectMapper) {
    return new DailyMarketReferenceArtifactLoader(resourceLoader, objectMapper).load(properties);
  }

  /** Resolves every new admission from the immutable artifact loaded at startup. */
  @Bean
  AdmissionRoutingPolicyResolver admissionRoutingPolicyResolver(
      VerifiedMarketReferenceArtifact artifact) {
    return new DailyArtifactAdmissionRoutingResolver(artifact);
  }

  /** Creates the deterministic barrier records for the same artifact that Admission resolves. */
  @Bean
  MatchingBarrierOutboxFactory matchingBarrierOutboxFactory(
      KafkaProperties kafkaProperties,
      VerifiedMarketReferenceArtifact artifact,
      MarketReferenceArtifactProperties properties,
      java.time.Clock riskServiceClock) {
    return new MatchingBarrierOutboxFactory(
        kafkaProperties.topics().matchingCommands(),
        artifact,
        properties.matchingImageDigest(),
        riskServiceClock);
  }

  /** Makes Open and Close Barrier publication available to Gateway operational coordination. */
  @Bean
  TradingSessionBarrierService tradingSessionBarrierService(
      MatchingBarrierOutboxFactory barriers,
      OutboxRepository outbox,
      TransactionTemplate riskTransactionTemplate) {
    return new TradingSessionBarrierService(barriers, outbox, riskTransactionTemplate);
  }

  /** Exposes the identity that Risk has verified for Gateway readiness composition. */
  @Bean("riskMarketReferenceArtifactReadyHealthIndicator")
  HealthIndicator riskMarketReferenceArtifactReadyHealthIndicator(
      VerifiedMarketReferenceArtifact artifact) {
    return () ->
        Health.up()
            .withDetail("tradingDay", artifact.identity().tradingDay().toString())
            .withDetail("contentSha256", artifact.identity().contentSha256())
            .withDetail(
                "routingAlgorithmVersion", artifact.artifact().routingPolicy().algorithmVersion())
            .build();
  }
}
