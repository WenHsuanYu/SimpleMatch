package com.simplematch.riskservice.submission;

import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class SubmissionCommandTest {
  @Test
  void exposesOnlyGroupedConstructorParameters() {
    final Constructor<?>[] constructors = SubmissionCommand.class.getConstructors();

    assertThat(constructors)
        .singleElement()
        .extracting(Constructor::getParameterTypes)
        .isEqualTo(
            new Class<?>[] {
              SubmissionCommand.RequestMetadata.class, SubmissionCommand.OrderDetails.class
            });
  }

  @Test
  void createBuildsPayloadFromGroupedParameters() {
    final SubmissionCommand command =
        SubmissionCommand.create(
            new SubmissionCommand.RequestMetadata(
                normalize("cmd-1"), "O-C1", "ACC-1", "CLIENT", "SIMPLEMATCH", "C1", "OC1"),
            new SubmissionCommand.OrderDetails(
                "AAPL",
                Side.SIDE_BUY,
                "10",
                "101.25",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD));

    final SubmissionCommand.RequestIdentity identity = command.requestMetadata().identity();
    final SubmissionCommand.FixIdentity fixIdentity = command.requestMetadata().fixIdentity();
    final SubmissionCommand.OrderDetails order = command.orderDetails();

    assertThat(identity.commandId().value()).isEqualTo(normalize("cmd-1"));
    assertThat(identity.commandId())
        .isEqualTo(new SubmissionCommand.CommandId(normalize("cmd-1")));
    assertThat(identity.orderId()).isEqualTo(new SubmissionCommand.OrderId("O-C1"));
    assertThat(identity.accountId()).isEqualTo(new SubmissionCommand.AccountId("ACC-1"));
    assertThat(fixIdentity.senderCompId()).isEqualTo(new SubmissionCommand.SenderCompId("CLIENT"));
    assertThat(fixIdentity.targetCompId())
        .isEqualTo(new SubmissionCommand.TargetCompId("SIMPLEMATCH"));
    assertThat(fixIdentity.clOrdId()).isEqualTo(new SubmissionCommand.ClOrdId("C1"));
    assertThat(fixIdentity.clOrdId().value()).isEqualTo("C1");
    assertThat(order.symbol()).isEqualTo("AAPL");
    assertThat(order.quantity()).isEqualTo(new SubmissionCommand.Quantity("10"));
    assertThat(order.price()).isEqualTo(new SubmissionCommand.Price("101.25"));
    assertThat(order.orderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(fixIdentity.origClOrdId().value()).isEqualTo("OC1");
  }

  @Test
  void createNormalizesNullGroupedParametersToAnEmptyPayload() {
    final SubmissionCommand command =
        SubmissionCommand.create(
            new SubmissionCommand.RequestMetadata(null, null, null, null, null, null, null),
            new SubmissionCommand.OrderDetails(
                null, null, (String) null, (String) null, null, null));

    assertThat(command.hasNoPayloadFields()).isTrue();
    assertThat(command.requestMetadata().identity().hasNoPayloadFields()).isTrue();
    assertThat(command.requestMetadata().fixIdentity().hasNoPayloadFields()).isTrue();
    assertThat(command.orderDetails().hasNoPayloadFields()).isTrue();
  }

  @Test
  void unspecifiedPayloadHasNoPayloadFields() {
    final SubmissionCommand command = SubmissionCommand.unspecified();

    assertThat(command.hasNoPayloadFields()).isTrue();
  }

  @Test
  void payloadWithClOrdIdIsNotBlankPayload() {
    final SubmissionCommand command =
        SubmissionCommand.create(
            new SubmissionCommand.RequestMetadata("", "", "", "", "", "C1", ""),
            SubmissionCommand.OrderDetails.empty());

    assertThat(command.hasNoPayloadFields()).isFalse();
  }
}
