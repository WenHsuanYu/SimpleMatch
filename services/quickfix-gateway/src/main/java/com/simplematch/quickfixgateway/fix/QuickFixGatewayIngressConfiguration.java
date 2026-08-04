package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the gateway's concrete new-order, cancel, and dispatch modules. */
@Configuration
public class QuickFixGatewayIngressConfiguration {
  @Bean
  CommandIdGenerator commandIdGenerator() {
    return new CommandIdGenerator();
  }

  @Bean
  RiskSubmissionResponder riskSubmissionResponder(
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper) {
    return new RiskSubmissionResponder(
        riskSubmissionClient, fixSessionMessageSender, fixMessageMapper);
  }

  @Bean
  FixCompatibilityCommandPublisher compatibilityCommandPublisher(
      OrdersCommandPublisher ordersCommandPublisher) {
    return new FixCompatibilityCommandPublisher(ordersCommandPublisher);
  }

  @Bean
  NewOrderCommandPreparer newOrderCommandPreparer(
      CommandIdGenerator commandIdGenerator, Clock quickFixGatewayClock) {
    return new NewOrderCommandPreparer(commandIdGenerator, quickFixGatewayClock);
  }

  @Bean
  NewOrderDurableAdmission newOrderDurableAdmission(
      WalAppender walAppender, RiskSubmissionResponder riskSubmissionResponder) {
    return new NewOrderDurableAdmission(walAppender, riskSubmissionResponder);
  }

  @Bean
  AcceptedNewOrderResponder acceptedNewOrderResponder(
      OrderSessionRegistry orderSessionRegistry,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      FixCompatibilityCommandPublisher compatibilityPublisher) {
    return new AcceptedNewOrderResponder(
        orderSessionRegistry,
        fixSessionMessageSender,
        fixMessageMapper,
        compatibilityPublisher);
  }

  @Bean
  NewOrderRejectionResponder newOrderRejectionResponder(
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      CommandIdGenerator commandIdGenerator) {
    return new NewOrderRejectionResponder(
        fixSessionMessageSender, fixMessageMapper, commandIdGenerator);
  }

  @Bean
  NewOrderFixMessageHandler newOrderFixMessageHandler(
      NewOrderCommandPreparer commandPreparer,
      NewOrderDurableAdmission durableAdmission,
      AcceptedNewOrderResponder acceptedResponder,
      NewOrderRejectionResponder rejectionResponder) {
    return new NewOrderFixMessageHandler(
        commandPreparer, durableAdmission, acceptedResponder, rejectionResponder);
  }

  @Bean
  CancelOrderFixMessageHandler cancelOrderFixMessageHandler(
      WalAppender walAppender,
      OrderSessionRegistry orderSessionRegistry,
      RiskSubmissionResponder riskSubmissionResponder,
      FixCompatibilityCommandPublisher compatibilityPublisher,
      CommandIdGenerator commandIdGenerator,
      Clock quickFixGatewayClock) {
    return new CancelOrderFixMessageHandler(
        walAppender,
        orderSessionRegistry,
        riskSubmissionResponder,
        compatibilityPublisher,
        commandIdGenerator,
        quickFixGatewayClock);
  }

  @Bean
  InboundFixMessageHandler inboundFixMessageHandler(
      NewOrderFixMessageHandler newOrderHandler,
      CancelOrderFixMessageHandler cancelOrderHandler) {
    return new InboundFixMessageHandler(newOrderHandler, cancelOrderHandler);
  }
}
