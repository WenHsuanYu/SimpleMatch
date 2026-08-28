package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.risk.RiskOrderIdentityDeriver;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskTestSupport;
import com.simplematch.quickfixgateway.wal.WalAppender;
import com.simplematch.quickfixgateway.wal.WalDurableCommandWriter;
import com.simplematch.quickfixgateway.wal.WalRecoveryJournal;
import java.time.Clock;

/** Composes the same concrete ingress modules used by the Spring configuration in tests. */
final class QuickFixIngressTestFixture {
  private QuickFixIngressTestFixture() {}

  /** Creates the inbound dispatcher with its new-order and cancel durable paths. */
  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock) {
    final GatewayAdmissionGate admissionGate = new GatewayAdmissionGate();
    admissionGate.open();
    return compose(
        walAppender,
        riskSubmissionClient,
        sender,
        registry,
        mapper,
        clock,
        admissionGate,
        new RiskOrderIdentityDeriver());
  }

  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock,
      RiskOrderIdentityDeriver orderIdentityDeriver) {
    final GatewayAdmissionGate admissionGate = new GatewayAdmissionGate();
    admissionGate.open();
    return compose(
        walAppender,
        riskSubmissionClient,
        sender,
        registry,
        mapper,
        clock,
        admissionGate,
        orderIdentityDeriver);
  }

  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock,
      GatewayAdmissionGate admissionGate) {
    return compose(
        walAppender,
        riskSubmissionClient,
        sender,
        registry,
        mapper,
        clock,
        admissionGate,
        new RiskOrderIdentityDeriver());
  }

  static InboundFixMessageHandler compose(
      WalAppender walAppender,
      RiskSubmissionClient riskSubmissionClient,
      FixSessionMessageSender sender,
      OrderSessionRegistry registry,
      FixMessageMapper mapper,
      Clock clock,
      GatewayAdmissionGate admissionGate,
      RiskOrderIdentityDeriver orderIdentityDeriver) {
    final CommandIdGenerator commandIdGenerator = new CommandIdGenerator();
    final WalRecoveryJournal recoveryJournal =
        new WalRecoveryJournal(WalRecoveryJournal.pathFor(walAppender.walPath()));
    final WalDurableCommandWriter durableCommandWriter =
        new WalDurableCommandWriter(walAppender, recoveryJournal);
    final RiskSubmissionResponder riskSubmissionResponder =
        new RiskSubmissionResponder(
            RiskTestSupport.submitter(riskSubmissionClient, orderIdentityDeriver),
            sender,
            mapper,
            recoveryJournal);
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
