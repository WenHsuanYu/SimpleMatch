package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import quickfix.SessionID;

public final class OrderSessionRegistry implements ExecutionSessionResolver {
  private final ConcurrentHashMap<String, OrderSessionState> states = new ConcurrentHashMap<>();
  private final Set<String> seenExecIds = ConcurrentHashMap.newKeySet();

  public void registerAcceptedOrder(SessionID sessionId, WalRecord walRecord, char ordStatus) {
    states.put(
        walRecord.orderId(),
        new OrderSessionState(
            sessionId,
            walRecord.orderId(),
            walRecord.accountId(),
            walRecord.clOrdId(),
            walRecord.symbol(),
            walRecord.side(),
            walRecord.quantity(),
            ordStatus));
  }

  public void registerCancelRequest(SessionID sessionId, WalRecord walRecord) {
    states.compute(
        walRecord.orderId(),
        (orderId, existing) -> {
          final OrderSessionState state =
              existing == null
                  ? new OrderSessionState(
                      sessionId,
                      walRecord.orderId(),
                      walRecord.accountId(),
                      walRecord.origClOrdId(),
                      walRecord.symbol(),
                      walRecord.side(),
                      walRecord.quantity(),
                      'A')
                  : existing;
          state.lastCancelRequest(
              new OrderSessionState.CancelRequestState(
                  walRecord.clOrdId(), walRecord.origClOrdId()));
          return state;
        });
  }

  public Optional<OrderSessionState> find(String orderId) {
    return Optional.ofNullable(states.get(orderId));
  }

  @Override
  public Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent) {
    return find(executionEvent.getOrderId()).map(OrderSessionState::sessionId);
  }

  public boolean markExecutionSeen(String execId) {
    return seenExecIds.add(execId);
  }

  public void applyExecution(ExecutionEvent executionEvent) {
    final OrderSessionState state = states.get(executionEvent.getOrderId());
    if (state == null) {
      return;
    }

    state.currentOrdStatus(
        mapOrdStatus(executionEvent.getExecutionType(), state.currentOrdStatus()));
    if (executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCELED
        || executionEvent.getExecutionType() == ExecutionType.EXECUTION_TYPE_CANCEL_REJECTED) {
      state.lastCancelRequest(null);
    }
  }

  private char mapOrdStatus(ExecutionType executionType, char fallback) {
    return switch (executionType) {
      case EXECUTION_TYPE_PENDING_NEW -> 'A';
      case EXECUTION_TYPE_NEW -> '0';
      case EXECUTION_TYPE_PARTIAL_FILL -> '1';
      case EXECUTION_TYPE_FILL -> '2';
      case EXECUTION_TYPE_CANCELED -> '4';
      case EXECUTION_TYPE_REJECTED -> '8';
      case EXECUTION_TYPE_CANCEL_REJECTED, EXECUTION_TYPE_UNSPECIFIED -> fallback;
      default -> fallback;
    };
  }
}
