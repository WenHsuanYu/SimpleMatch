package com.simplematch.riskservice.submission;

import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;

final class SubmissionCommandFixtures {
  private static final String DEFAULT_ACCOUNT_ID = "ACC-1";
  private static final String DEFAULT_SENDER_COMP_ID = "CLIENT";
  private static final String DEFAULT_TARGET_COMP_ID = "SIMPLEMATCH";
  private static final String DEFAULT_SYMBOL = "AAPL";
  private static final String DEFAULT_QUANTITY = "10";
  private static final String DEFAULT_PRICE = "101.25";

  private SubmissionCommandFixtures() {
  }

  static SubmissionCommand newOrderPayload(String commandId, String orderId, String clOrdId) {
    return newOrderPayload(commandId, orderId, clOrdId, DEFAULT_PRICE, OrderType.ORDER_TYPE_LIMIT);
  }

  static SubmissionCommand newOrderPayload(
      String commandId,
      String orderId,
      String clOrdId,
      String price,
      OrderType orderType) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clOrdId, ""),
        new SubmissionCommand.OrderDetails(
            DEFAULT_SYMBOL,
            Side.SIDE_BUY,
            DEFAULT_QUANTITY,
            price,
            orderType,
            TimeInForce.TIME_IN_FORCE_ROD));
  }

  static ResolvedSubmissionCommand resolvedNewOrder(String commandId, String orderId, String clOrdId) {
    return resolvedNewOrder(commandId, orderId, clOrdId, DEFAULT_PRICE, OrderType.ORDER_TYPE_LIMIT);
  }

  static ResolvedSubmissionCommand resolvedNewOrder(
      String commandId,
      String orderId,
      String clOrdId,
      String price,
      OrderType orderType) {
    return new ResolvedSubmissionCommand(
        newOrderPayload(commandId, orderId, clOrdId, price, orderType),
        CommandType.COMMAND_TYPE_NEW);
  }

  static SubmissionCommand cancelOrderPayload(
      String commandId,
      String orderId,
      String clOrdId,
      String origClOrdId) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clOrdId, origClOrdId),
        SubmissionCommand.OrderDetails.empty());
  }

  static ResolvedSubmissionCommand resolvedCancelOrder(
      String commandId,
      String orderId,
      String clOrdId,
      String origClOrdId) {
    return new ResolvedSubmissionCommand(
        SubmissionCommand.create(
            requestMetadata(commandId, orderId, clOrdId, origClOrdId),
            new SubmissionCommand.OrderDetails(
                DEFAULT_SYMBOL,
                Side.SIDE_BUY,
                DEFAULT_QUANTITY,
                DEFAULT_PRICE,
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        CommandType.COMMAND_TYPE_CANCEL);
  }

  private static SubmissionCommand.RequestMetadata requestMetadata(
      String commandId,
      String orderId,
      String clOrdId,
      String origClOrdId) {
    return new SubmissionCommand.RequestMetadata(
      normalize(commandId),
        orderId,
        DEFAULT_ACCOUNT_ID,
        DEFAULT_SENDER_COMP_ID,
        DEFAULT_TARGET_COMP_ID,
        clOrdId,
        origClOrdId);
  }
}