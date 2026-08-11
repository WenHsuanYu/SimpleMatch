package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import quickfix.SessionID;

/**
 * Tracks the session context needed to render asynchronous matching executions.
 *
 * <p>Acceptance, cancellation, execution de-duplication, and lifecycle transitions must observe the
 * same in-memory session state. The small named methods keep those distinct operations clear
 * without exposing the concurrent maps as separate mutable collaborators.
 */
public final class OrderSessionRegistry implements ExecutionSessionResolver {
  private final ConcurrentHashMap<String, OrderSessionState> states = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, WalRecord> admittedOrders = new ConcurrentHashMap<>();
  private final Set<String> seenExecIds = ConcurrentHashMap.newKeySet();

  /** Registers a newly admitted order with the originating FIX session. */
  public void registerAcceptedOrder(SessionID sessionId, WalRecord walRecord, char ordStatus) {
    admittedOrders.putIfAbsent(walRecord.orderId(), walRecord);
    states.put(
        walRecord.orderId(),
        new OrderSessionState(
            sessionId,
            walRecord.accountId(),
            FixOrderSnapshot.from(walRecord),
            new OrderSessionLifecycle(ordStatus)));
  }

  /** Returns the first accepted command for an order identity, when this owner still has it. */
  public Optional<WalRecord> findAdmittedOrder(String orderId) {
    return Optional.ofNullable(admittedOrders.get(orderId));
  }

  /** Registers a cancel request and retains its client correlation identifiers. */
  public void registerCancelRequest(SessionID sessionId, WalRecord walRecord) {
    states.compute(
        walRecord.orderId(),
        (orderId, existing) -> {
          final OrderSessionState state =
              existing == null
                  ? new OrderSessionState(
                      sessionId,
                      walRecord.accountId(),
                      FixOrderSnapshot.cancelFallback(walRecord),
                      new OrderSessionLifecycle('A'))
                  : existing;
          return state.withLifecycle(
              state
                  .lifecycle()
                  .withLastCancelRequest(
                      new OrderSessionState.CancelRequestState(
                          walRecord.clOrdId(), walRecord.origClOrdId())));
        });
  }

  /** Finds the session state for an order, when it is still known locally. */
  public Optional<OrderSessionState> find(String orderId) {
    return Optional.ofNullable(states.get(orderId));
  }

  @Override
  public Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent) {
    return find(executionEvent.getOrderId()).map(OrderSessionState::sessionId);
  }

  /** Returns whether an execution identifier has not yet been processed. */
  public boolean markExecutionSeen(String execId) {
    return seenExecIds.add(execId);
  }

  /** Returns whether an execution identifier has already produced a client-facing effect. */
  public boolean hasExecutionBeenSeen(String execId) {
    return seenExecIds.contains(execId);
  }

  /** Returns whether an execution can advance the tracked order lifecycle. */
  public boolean acceptsExecution(ExecutionEvent executionEvent) {
    return find(executionEvent.getOrderId())
        .map(state -> state.lifecycle().accepts(executionEvent.getExecutionType()))
        .orElse(false);
  }

  /** Applies an execution outcome to the locally tracked order session state. */
  public boolean applyExecution(ExecutionEvent executionEvent) {
    final boolean[] applied = {false};
    states.computeIfPresent(
        executionEvent.getOrderId(),
        (orderId, state) -> {
          if (!state.lifecycle().accepts(executionEvent.getExecutionType())) {
            return state;
          }
          applied[0] = true;
          return state.withLifecycle(state.lifecycle().after(executionEvent.getExecutionType()));
        });
    return applied[0];
  }

  /**
   * Records a final Matching Event lifecycle transition after its durable FIX intent was sent.
   *
   * <p>The registry is only an ingress-session cache; the delivery ledger remains the restart-safe
   * authority. Updating the cache keeps later cancel acknowledgement status aligned with the last
   * client-visible report.
   */
  public void recordFinalOrderStatus(String orderId, char orderStatus) {
    states.computeIfPresent(
        orderId,
        (ignored, state) ->
            state.withLifecycle(
                state.lifecycle().withCurrentOrdStatus(orderStatus).withLastCancelRequest(null)));
  }
}
