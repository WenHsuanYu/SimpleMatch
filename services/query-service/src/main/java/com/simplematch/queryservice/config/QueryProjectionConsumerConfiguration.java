package com.simplematch.queryservice.config;

import com.simplematch.queryservice.kafka.QueryProjectionKafkaConsumer;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.runtime.QueryProjectionConsumerControl;
import com.simplematch.queryservice.store.QueryProjectionStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/** Wires the two asynchronous query consumers and their shared rebuild lifecycle. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class QueryProjectionConsumerConfiguration {
  /** Creates the two independent query consumer groups. */
  @Bean
  QueryProjectionKafkaConsumer queryProjectionKafkaConsumer(
      QueryProjectionApplicationService projectionService,
      QueryProjectionStore projectionStore,
      Clock queryServiceClock) {
    return new QueryProjectionKafkaConsumer(projectionService, projectionStore, queryServiceClock);
  }

  /** Stops both named non-critical listeners before an operator resets query state. */
  @Bean
  QueryProjectionConsumerControl queryProjectionConsumerControl(
      KafkaListenerEndpointRegistry listenerRegistry) {
    return () -> {
      final MessageListenerContainer matching =
          requireListener(listenerRegistry, QueryProjectionKafkaConsumer.MATCHING_LISTENER_ID);
      final MessageListenerContainer account =
          requireListener(listenerRegistry, QueryProjectionKafkaConsumer.ACCOUNT_LISTENER_ID);
      matching.stop();
      account.stop();
    };
  }

  private MessageListenerContainer requireListener(
      KafkaListenerEndpointRegistry listenerRegistry, String listenerId) {
    final MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
    if (container == null) {
      throw new IllegalStateException("query listener is not registered: " + listenerId);
    }
    return container;
  }
}
