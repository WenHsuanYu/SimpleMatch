package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.risk.RiskCommandSubmitter;
import com.simplematch.quickfixgateway.wal.WalDurableCommandWriter;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
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
      RiskCommandSubmitter riskCommandSubmitter,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper,
      WalRecoveryJournal recoveryJournal) {
    return new RiskSubmissionResponder(
        riskCommandSubmitter, fixSessionMessageSender, fixMessageMapper, recoveryJournal);
  }

  @Bean
  NewOrderCommandPreparer newOrderCommandPreparer(
      CommandIdGenerator commandIdGenerator, Clock quickFixGatewayClock) {
    return new NewOrderCommandPreparer(commandIdGenerator, quickFixGatewayClock);
  }

  @Bean
  NewOrderDurableAdmission newOrderDurableAdmission(
      WalDurableCommandWriter durableCommandWriter,
      RiskSubmissionResponder riskSubmissionResponder) {
    return new NewOrderDurableAdmission(durableCommandWriter, riskSubmissionResponder);
  }

  @Bean
  AcceptedNewOrderResponder acceptedNewOrderResponder(
      OrderSessionRegistry orderSessionRegistry,
      FixSessionMessageSender fixSessionMessageSender,
      FixMessageMapper fixMessageMapper) {
    return new AcceptedNewOrderResponder(
        orderSessionRegistry, fixSessionMessageSender, fixMessageMapper);
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
      NewOrderRejectionResponder rejectionResponder,
      GatewayAdmissionGate admissionGate,
      Clock quickFixGatewayClock,
      OrderSessionRegistry orderSessionRegistry) {
    return new NewOrderFixMessageHandler(
        commandPreparer,
        durableAdmission,
        acceptedResponder,
        rejectionResponder,
        admissionGate,
        quickFixGatewayClock,
        orderSessionRegistry);
  }

  @Bean
  CancelOrderFixMessageHandler cancelOrderFixMessageHandler(
      WalDurableCommandWriter durableCommandWriter,
      OrderSessionRegistry orderSessionRegistry,
      RiskSubmissionResponder riskSubmissionResponder,
      CommandIdGenerator commandIdGenerator,
      Clock quickFixGatewayClock,
      GatewayAdmissionGate admissionGate) {
    return new CancelOrderFixMessageHandler(
        durableCommandWriter,
        orderSessionRegistry,
        riskSubmissionResponder,
        commandIdGenerator,
        quickFixGatewayClock,
        admissionGate);
  }

  @Bean
  InboundFixMessageHandler inboundFixMessageHandler(
      NewOrderFixMessageHandler newOrderHandler,
      CancelOrderFixMessageHandler cancelOrderHandler) {
    return new InboundFixMessageHandler(newOrderHandler, cancelOrderHandler);
  }
}
