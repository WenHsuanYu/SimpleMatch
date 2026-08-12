package com.simplematch.marketdataprojection.config;

import com.simplematch.marketdataprojection.kafka.KafkaMarketDataEventPublisher;
import com.simplematch.marketdataprojection.kafka.MarketDataEventPublisher;
import com.simplematch.marketdataprojection.kafka.MarketDataOutboxDispatcher;
import com.simplematch.marketdataprojection.store.MarketDataProjectionStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires the projection's Kafka output adapters and shared runtime scheduling. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(MarketDataProjectionProperties.class)
public class MarketDataProjectionRuntimeConfiguration {
  /** Creates the at-least-once Kafka publication adapter only when runtime output is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.marketdata-events.enabled",
      havingValue = "true")
  MarketDataEventPublisher marketDataEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
    return new KafkaMarketDataEventPublisher(kafkaTemplate);
  }

  /** Creates the scheduled durable outbox dispatcher only when market-data output is enabled. */
  @Bean
  @ConditionalOnProperty(
      name = "simplematch.market-data-projection.marketdata-events.enabled",
      havingValue = "true")
  ScheduledMarketDataOutboxDispatcher scheduledMarketDataOutboxDispatcher(
      MarketDataProjectionStore marketDataProjectionStore,
      MarketDataEventPublisher marketDataEventPublisher,
      Clock marketDataProjectionClock,
      MarketDataProjectionProperties properties) {
    return new ScheduledMarketDataOutboxDispatcher(
        new MarketDataOutboxDispatcher(
            marketDataProjectionStore,
            marketDataEventPublisher,
            marketDataProjectionClock,
            properties.marketdataEvents().dispatchBatchSize()));
  }

  /**
   * Calls the dispatcher from Spring scheduling without leaking scheduling mechanics into its
   * interface.
   */
  public static final class ScheduledMarketDataOutboxDispatcher {
    private final MarketDataOutboxDispatcher dispatcher;

    private ScheduledMarketDataOutboxDispatcher(MarketDataOutboxDispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    /** Runs one bounded output batch. */
    @Scheduled(
        fixedDelayString =
            "${simplematch.market-data-projection.marketdata-events.dispatch-interval:1s}")
    public void dispatch() {
      dispatcher.dispatchPending();
    }
  }
}
