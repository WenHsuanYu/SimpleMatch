package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.wal.WalAppender;
import java.time.Clock;

/** Composes the same concrete ingress modules used by the Spring configuration in tests. */
final class QuickFixIngressTestFixture {
  private QuickFixIngressTestFixture() {}

  /**
   * Creates the inbound dispatcher with its new-order and cancel durable paths.
   *
   * @param walAppender test WAL adapter
   * @param ordersCommandPublisher compatibility publisher
   * @param riskSubmissionClient risk admission client
   * @param sender outbound FIX sender
   * @param registry session correlation registry
   * @param mapper outbound FIX mapper
   * @param clock test clock
   * @return composed inbound dispatcher
   */
  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      OrdersCommandPublisher ordersCommandPublisher,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock) {
    final CommandIdGenerator commandIdGenerator = new CommandIdGenerator();
    final RiskSubmissionResponder riskSubmissionResponder =
        new RiskSubmissionResponder(riskSubmissionClient, sender, mapper);
    final FixCompatibilityCommandPublisher compatibilityPublisher =
        new FixCompatibilityCommandPublisher(ordersCommandPublisher);
    return new InboundFixMessageHandler(
        new NewOrderFixMessageHandler(
            new NewOrderCommandPreparer(commandIdGenerator, clock),
            new NewOrderDurableAdmission(walAppender, riskSubmissionResponder),
            new AcceptedNewOrderResponder(
                registry, sender, mapper, compatibilityPublisher),
            new NewOrderRejectionResponder(sender, mapper, commandIdGenerator)),
        new CancelOrderFixMessageHandler(
            walAppender,
            registry,
            riskSubmissionResponder,
            compatibilityPublisher,
            commandIdGenerator,
            clock));
  }
}
