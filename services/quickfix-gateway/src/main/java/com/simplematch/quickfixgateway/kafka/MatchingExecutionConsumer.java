package com.simplematch.quickfixgateway.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.fix.ExecutionSessionResolver;
import com.simplematch.quickfixgateway.fix.FixMessageMapper;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.fix.OrderSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import quickfix.Message;
import quickfix.SessionID;

/** Converts matching execution events into FIX responses for their originating sessions. */
public final class MatchingExecutionConsumer {
  private static final Logger logger = LoggerFactory.getLogger(MatchingExecutionConsumer.class);

  private final ExecutionSessionResolver executionSessionResolver;
  private final OrderSessionRegistry orderSessionRegistry;
  private final FixMessageMapper fixMessageMapper;
  private final FixSessionMessageSender fixSessionMessageSender;

  /**
   * Creates the consumer with session lookup, deduplication, rendering, and sending collaborators.
   */
  public MatchingExecutionConsumer(
      ExecutionSessionResolver executionSessionResolver,
      OrderSessionRegistry orderSessionRegistry,
      FixMessageMapper fixMessageMapper,
      FixSessionMessageSender fixSessionMessageSender) {
    this.executionSessionResolver = executionSessionResolver;
    this.orderSessionRegistry = orderSessionRegistry;
    this.fixMessageMapper = fixMessageMapper;
    this.fixSessionMessageSender = fixSessionMessageSender;
  }

  /** Renders and sends one deduplicated matching execution event. */
  @KafkaListener(topics = "${simplematch.kafka.topics.matching-executions:matching.executions}")
  public void onExecution(byte[] payload) throws InvalidProtocolBufferException {
    final ExecutionEvent executionEvent = ExecutionEvent.parseFrom(payload);
    ExecutionEventRequirements.validate(executionEvent);

    if (!orderSessionRegistry.markExecutionSeen(executionEvent.getExecId())) {
      logger.debug("skip duplicate execution event exec_id={}", executionEvent.getExecId());
      return;
    }

    final SessionID sessionId =
        executionSessionResolver.resolveSessionId(executionEvent).orElse(null);
    final OrderSessionState state =
        orderSessionRegistry.find(executionEvent.getOrderId()).orElse(null);
    if (sessionId == null || state == null) {
      logger.warn(
          "skip execution event without order session context order_id={} exec_id={}",
          executionEvent.getOrderId(),
          executionEvent.getExecId());
      return;
    }

    final Message outbound =
        executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED
            ? fixMessageMapper.buildOrderCancelReject(executionEvent, state)
            : fixMessageMapper.buildExecutionReport(executionEvent, state);
    fixSessionMessageSender.send(sessionId, outbound);
    orderSessionRegistry.applyExecution(executionEvent);
  }
}
