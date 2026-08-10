package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskTestSupport;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalDurableCommandWriter;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import java.time.Clock;
import java.util.Objects;

/** Composes the same concrete ingress modules used by the Spring configuration in tests. */
final class QuickFixIngressTestFixture {
  private QuickFixIngressTestFixture() {}

  /** Creates the inbound dispatcher with its new-order and cancel durable paths. */
  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock) {
    return compose(
        walAppender,
        ordersCommandPublisher,
        riskSubmissionClient,
        sender,
        registry,
        mapper,
        clock,
        new GatewayAdmissionGate());
  }

  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock,
      GatewayAdmissionGate admissionGate) {
    Objects.requireNonNull(ordersCommandPublisher, "ordersCommandPublisher");
    final CommandIdGenerator commandIdGenerator = new CommandIdGenerator();
    final WalRecoveryJournal recoveryJournal =
        new WalRecoveryJournal(WalRecoveryJournal.pathFor(walAppender.walPath()));
    final WalDurableCommandWriter durableCommandWriter =
        new WalDurableCommandWriter(walAppender, recoveryJournal);
    final RiskSubmissionResponder riskSubmissionResponder =
        new RiskSubmissionResponder(
            RiskTestSupport.submitter(riskSubmissionClient), sender, mapper, recoveryJournal);
    return new InboundFixMessageHandler(
        new NewOrderFixMessageHandler(
            new NewOrderCommandPreparer(commandIdGenerator, clock),
            new NewOrderDurableAdmission(durableCommandWriter, riskSubmissionResponder),
            new AcceptedNewOrderResponder(registry, sender, mapper),
            new NewOrderRejectionResponder(sender, mapper, commandIdGenerator),
            admissionGate,
            clock,
            registry),
        new CancelOrderFixMessageHandler(
            durableCommandWriter,
            registry,
            riskSubmissionResponder,
            commandIdGenerator,
            clock,
            admissionGate));
  }
}
