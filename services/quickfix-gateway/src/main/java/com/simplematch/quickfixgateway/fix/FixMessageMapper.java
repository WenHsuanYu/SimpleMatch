package com.simplematch.quickfixgateway.fix;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

/** Provides the stable gateway seam for rendering FIX 4.4 outcome messages. */
public final class FixMessageMapper {
  private final FixExecutionReportMapper executionReportMapper;
  private final FixCancelRejectMapper cancelRejectMapper;

  /** Creates the FIX outcome mapper. */
  public FixMessageMapper() {
    executionReportMapper = new FixExecutionReportMapper();
    cancelRejectMapper = new FixCancelRejectMapper();
  }

  /** Builds a Pending New execution report from explicit FIX adapter values. */
  public ExecutionReport buildPendingNew(FixOrderSnapshot order, FixExecutionIdentity execution) {
    return executionReportMapper.buildPendingNew(order, execution);
  }

  /** Builds a Pending New execution report with a client-safe explanation. */
  public ExecutionReport buildPendingNew(
      FixOrderSnapshot order, FixExecutionIdentity execution, String text) {
    return executionReportMapper.buildPendingNew(order, execution, text);
  }

  /** Builds a rejected execution report from explicit FIX adapter values. */
  public ExecutionReport buildRejected(
      FixOrderSnapshot order, FixExecutionIdentity execution, String text) {
    return executionReportMapper.buildRejected(order, execution, text);
  }

  /** Builds a rejection from whatever order facts are available in malformed inbound FIX. */
  ExecutionReport buildRejectedInboundOrder(
      Message message, FixExecutionIdentity execution, String text) throws FieldNotFound {
    return executionReportMapper.buildRejected(
        FixInboundOrderRejection.from(message), execution, text);
  }

  /** Renders a cancel rejection from the explicit FIX correlation fields. */
  public OrderCancelReject buildOrderCancelReject(
      String orderId,
      String cancelClientOrderId,
      String originalClientOrderId,
      char ordStatus,
      String text) {
    return cancelRejectMapper.buildOrderCancelReject(
        orderId, cancelClientOrderId, originalClientOrderId, ordStatus, text);
  }
}
