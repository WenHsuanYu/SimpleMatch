package com.simplematch.riskservice.config;

import com.simplematch.config.KafkaProperties;
import com.simplematch.riskservice.admission.AdmissionRoutingPolicyResolver;
import com.simplematch.riskservice.admission.LocalAdmissionRoutingPolicyResolver;
import com.simplematch.riskservice.routing.JdbcRoutingPolicyProjectionRepository;
import com.simplematch.riskservice.routing.RiskRoutingPolicyReadinessHealthIndicator;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionRepository;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the Risk-local Routing Policy projection and its fail-closed readiness boundary. */
@Configuration(proxyBeanMethods = false)
public class RiskRoutingPolicyConfiguration {
  /** Creates the Risk-owned local projection repository. */
  @Bean
  RoutingPolicyProjectionRepository routingPolicyProjectionRepository(
      JdbcTemplate riskJdbcTemplate) {
    return new JdbcRoutingPolicyProjectionRepository(riskJdbcTemplate);
  }

  /** Creates the fail-closed Admission route selector over the local projection. */
  @Bean
  AdmissionRoutingPolicyResolver admissionRoutingPolicyResolver(
      RoutingPolicyProjectionRepository repository) {
    return new LocalAdmissionRoutingPolicyResolver(repository);
  }

  /** Creates the strict decoder and atomic activation service. */
  @Bean
  RoutingPolicyProjectionService routingPolicyProjectionService(
      RoutingPolicyProjectionRepository repository,
      TransactionTemplate riskTransactionTemplate,
      Clock riskServiceClock) {
    return new RoutingPolicyProjectionService(
        repository, riskTransactionTemplate, riskServiceClock);
  }

  /** Exposes fail-closed readiness until the applicable local policy is complete and compatible. */
  @Bean("riskRoutingPolicyReadyHealthIndicator")
  RiskRoutingPolicyReadinessHealthIndicator riskRoutingPolicyReadyHealthIndicator(
      RoutingPolicyProjectionRepository repository,
      Clock riskServiceClock,
      KafkaProperties kafkaProperties) {
    return new RiskRoutingPolicyReadinessHealthIndicator(
        repository,
        riskServiceClock,
        kafkaProperties.partitions().ordersValidated());
  }
}
