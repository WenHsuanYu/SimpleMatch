package com.simplematch.riskservice.submission;

final class SubmissionCommandFixtures {
  private static final String DEFAULT_ACCOUNT_ID = "ACC-1";
  private static final String DEFAULT_SESSION_ID = "FIX.4.4:CLIENT->SIMPLEMATCH";
  private static final String DEFAULT_SYMBOL = "AAPL";
  private static final String DEFAULT_QUANTITY = "10";
  private static final String DEFAULT_PRICE = "101.25";

  private SubmissionCommandFixtures() {
  }

  static SubmissionCommand newOrderPayload(String commandId, String orderId, String clientOrderId) {
    return newOrderPayload(commandId, orderId, clientOrderId, DEFAULT_PRICE, OrderType.ORDER_TYPE_LIMIT);
  }

  static SubmissionCommand newOrderPayload(
      String commandId,
      String orderId,
      String clientOrderId,
      String price,
      OrderType orderType) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clientOrderId, ""),
        new SubmissionCommand.OrderDetails(
            DEFAULT_SYMBOL,
            Side.SIDE_BUY,
            DEFAULT_QUANTITY,
            price,
            orderType,
            TimeInForce.TIME_IN_FORCE_ROD));
  }

  static ResolvedSubmissionCommand resolvedNewOrder(String commandId, String orderId, String clientOrderId) {
    return resolvedNewOrder(commandId, orderId, clientOrderId, DEFAULT_PRICE, OrderType.ORDER_TYPE_LIMIT);
  }

  static ResolvedSubmissionCommand resolvedNewOrder(
      String commandId,
      String orderId,
      String clientOrderId,
      String price,
      OrderType orderType) {
    return new ResolvedSubmissionCommand(
        newOrderPayload(commandId, orderId, clientOrderId, price, orderType),
        CommandType.COMMAND_TYPE_NEW);
  }

  static SubmissionCommand cancelOrderPayload(
      String commandId,
      String orderId,
      String clientOrderId,
      String originalClientOrderId) {
    return SubmissionCommand.create(
        requestMetadata(commandId, orderId, clientOrderId, originalClientOrderId),
        SubmissionCommand.OrderDetails.empty());
  }

  static ResolvedSubmissionCommand resolvedCancelOrder(
      String commandId,
      String orderId,
      String clientOrderId,
      String originalClientOrderId) {
    return new ResolvedSubmissionCommand(
        SubmissionCommand.create(
            requestMetadata(commandId, orderId, clientOrderId, originalClientOrderId),
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
      String clientOrderId,
      String originalClientOrderId) {
    return new SubmissionCommand.RequestMetadata(
        commandId,
        orderId,
        DEFAULT_ACCOUNT_ID,
        DEFAULT_SESSION_ID,
        clientOrderId,
        originalClientOrderId);
  }
}