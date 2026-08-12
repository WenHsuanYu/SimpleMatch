package com.simplematch.marketdatastreamer.config;

import com.simplematch.marketdatastreamer.stream.MarketDataGrpcService;
import com.simplematch.marketdatastreamer.stream.MarketDataSnapshotBroadcaster;
import com.simplematch.marketdatastreamer.stream.MarketDataSnapshotKafkaConsumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/** Wires the non-critical snapshot consumer and public stream adapters. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(MarketdataStreamerProperties.class)
public class MarketdataStreamerConfiguration {
  /** Creates the bounded fan-out seam shared by Kafka and gRPC adapters. */
  @Bean(destroyMethod = "close")
  MarketDataSnapshotBroadcaster marketDataSnapshotBroadcaster(
      MarketdataStreamerProperties properties) {
    return new MarketDataSnapshotBroadcaster(
        properties.subscriberQueueCapacity(), properties.maximumSubscribers());
  }

  /** Creates the Kafka consumer that feeds public snapshots. */
  @Bean
  MarketDataSnapshotKafkaConsumer marketDataSnapshotKafkaConsumer(
      MarketDataSnapshotBroadcaster broadcaster) {
    return new MarketDataSnapshotKafkaConsumer(broadcaster);
  }

  /** Creates the transport adapter for public snapshot subscriptions. */
  @Bean
  MarketDataGrpcService marketDataGrpcService(MarketDataSnapshotBroadcaster broadcaster) {
    return new MarketDataGrpcService(broadcaster);
  }
}
