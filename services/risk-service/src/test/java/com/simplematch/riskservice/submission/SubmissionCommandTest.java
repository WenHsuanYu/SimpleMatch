package com.simplematch.riskservice.submission;

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
        .isEqualTo(new Class<?>[] {
            SubmissionCommand.RequestMetadata.class,
            SubmissionCommand.OrderDetails.class
        });
  }

  @Test
  void createBuildsPayloadFromGroupedParameters() {
    final SubmissionCommand command = SubmissionCommand.create(
        new SubmissionCommand.RequestMetadata(
            "cmd-1",
            "O-C1",
            "ACC-1",
            "FIX.4.4:CLIENT->SIMPLEMATCH",
            "C1",
            "OC1"),
        new SubmissionCommand.OrderDetails(
            "AAPL",
            Side.SIDE_BUY,
            "10",
            "101.25",
            OrderType.ORDER_TYPE_LIMIT,
            TimeInForce.TIME_IN_FORCE_ROD));

    assertThat(command.commandId()).isEqualTo("cmd-1");
              assertThat(command.commandIdValue()).isEqualTo(new SubmissionCommand.CommandId("cmd-1"));
              assertThat(command.orderIdValue()).isEqualTo(new SubmissionCommand.OrderId("O-C1"));
              assertThat(command.accountIdValue()).isEqualTo(new SubmissionCommand.AccountId("ACC-1"));
    assertThat(command.sessionIdValue()).isEqualTo(new SubmissionCommand.SessionId("FIX.4.4:CLIENT->SIMPLEMATCH"));
              assertThat(command.clientOrderIdValue()).isEqualTo(new SubmissionCommand.ClientOrderId("C1"));
    assertThat(command.clientOrderId()).isEqualTo("C1");
    assertThat(command.symbol()).isEqualTo("AAPL");
              assertThat(command.quantityValue()).isEqualTo(new SubmissionCommand.Quantity("10"));
              assertThat(command.priceValue()).isEqualTo(new SubmissionCommand.Price("101.25"));
    assertThat(command.orderType()).isEqualTo(OrderType.ORDER_TYPE_LIMIT);
    assertThat(command.originalClientOrderId()).isEqualTo("OC1");
  }

  @Test
  void createNormalizesNullGroupedParametersToAnEmptyPayload() {
    final SubmissionCommand command = SubmissionCommand.create(
        new SubmissionCommand.RequestMetadata(null, null, null, null, null, null),
        new SubmissionCommand.OrderDetails(null, null, null, null, null, null));

    assertThat(command.hasNoPayloadFields()).isTrue();
    assertThat(command.commandIdValue().isBlank()).isTrue();
    assertThat(command.orderIdValue().isBlank()).isTrue();
    assertThat(command.accountIdValue().isBlank()).isTrue();
    assertThat(command.sessionIdValue().isBlank()).isTrue();
    assertThat(command.clientOrderIdValue().isBlank()).isTrue();
    assertThat(command.quantityValue().isBlank()).isTrue();
    assertThat(command.priceValue().isBlank()).isTrue();
  }

  @Test
  void unspecifiedPayloadHasNoPayloadFields() {
    final SubmissionCommand command = SubmissionCommand.unspecified();

    assertThat(command.hasNoPayloadFields()).isTrue();
  }

  @Test
  void payloadWithClientOrderIdIsNotBlankPayload() {
    final SubmissionCommand command = SubmissionCommand.create(
        new SubmissionCommand.RequestMetadata("", "", "", "", "C1", ""),
        SubmissionCommand.OrderDetails.empty());

    assertThat(command.hasNoPayloadFields()).isFalse();
  }
}