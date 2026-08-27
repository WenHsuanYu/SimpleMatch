package com.simplematch.marketdataprojection;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.EnvironmentProperties;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;

/** Verifies that the non-critical projection can boot without enabling Kafka or Redis delivery. */
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "simplematch.market-data-projection.matching-events.enabled=false",
      "simplematch.market-data-projection.marketdata-events.enabled=false",
      "simplematch.market-data-projection.redis.enabled=false"
    })
@ActiveProfiles("test")
class MarketDataProjectionApplicationTest {
  @Autowired private EnvironmentProperties environmentProperties;
  @Autowired private ProducerFactory<?, ?> producerFactory;

  @Test
  void loadsItsSharedPlatformConfiguration() {
    assertThat(environmentProperties.environment()).isEqualTo("test");
  }

  @Test
  void serializesVersionedSnapshotPayloadsAsExactBytes() {
    assertThat(producerFactory.getConfigurationProperties())
        .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
        .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
  }
}
