package com.simplematch.marketdatapublisher.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.config.KafkaProperties;
import com.simplematch.config.SimpleMatchUuids;
import com.simplematch.marketdatapublisher.health.RoutingPolicyReadinessHealthIndicator;
import com.simplematch.marketdatapublisher.routing.JdbcRoutingPolicyOutbox;
import com.simplematch.marketdatapublisher.routing.JdbcRoutingPolicyRepository;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyApplicationService;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyOutbox;
import com.simplematch.marketdatapublisher.routing.RoutingPolicyRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the cohesive Market Reference routing-policy publication boundary. */
@Configuration
public class MarketdataPublisherRoutingConfiguration {
  /** Creates routing-policy persistence operations for the Market Reference transaction. */
  @Bean
  RoutingPolicyRepository routingPolicyRepository(JdbcTemplate jdbcTemplate) {
    return new JdbcRoutingPolicyRepository(jdbcTemplate);
  }

  /** Creates the service-local outbox adapter for versioned routing policies. */
  @Bean
  RoutingPolicyOutbox routingPolicyOutbox(JdbcTemplate jdbcTemplate) {
    return new JdbcRoutingPolicyOutbox(jdbcTemplate);
  }

  /** Creates the transaction owner for complete routing-policy publication. */
  @Bean
  RoutingPolicyApplicationService routingPolicyApplicationService(
      RoutingPolicyRepository routingPolicyRepository,
      RoutingPolicyOutbox routingPolicyOutbox,
      ObjectMapper marketdataPublisherObjectMapper,
      Clock marketdataPublisherClock) {
    return new RoutingPolicyApplicationService(
        routingPolicyRepository,
        routingPolicyOutbox,
        marketdataPublisherObjectMapper,
        marketdataPublisherClock,
        SimpleMatchUuids::uuidV7);
  }

  /** Exposes fail-closed readiness for the current complete and topology-compatible policy. */
  @Bean("routingPolicyReadyHealthIndicator")
  RoutingPolicyReadinessHealthIndicator routingPolicyReadyHealthIndicator(
      RoutingPolicyRepository routingPolicyRepository,
      Clock marketdataPublisherClock,
      KafkaProperties kafkaProperties) {
    return new RoutingPolicyReadinessHealthIndicator(
        routingPolicyRepository,
        marketdataPublisherClock,
        kafkaProperties.partitions().ordersValidated());
  }
}
