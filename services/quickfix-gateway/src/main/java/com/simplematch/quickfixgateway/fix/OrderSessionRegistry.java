package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.quickfixgateway.risk.RiskOrderIdentityDeriver;
import com.simplematch.quickfixgateway.wal.WalRecord;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import quickfix.SessionID;

/**
 * Tracks the session context needed to render asynchronous matching executions.
 *
 * <p>Acceptance, cancellation, execution de-duplication, and lifecycle transitions must observe the
 * same in-memory session state. Canonical Risk/Matching order identity is the authoritative lookup
 * key, while FIX-facing OrderID remains available for session-scoped ingress correlation.
 */
public final class OrderSessionRegistry implements ExecutionSessionResolver {
  private final RiskOrderIdentityDeriver orderIdentityDeriver;
  private final ConcurrentHashMap<String, OrderSessionState> statesByCanonicalOrderId =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, WalRecord> admittedOrdersByCanonicalOrderId =
      new ConcurrentHashMap<>();
  private final OrderIdentityIndex identityIndex = new OrderIdentityIndex();
  private final Set<String> seenExecIds = ConcurrentHashMap.newKeySet();

  /** Creates a registry with the canonical Gateway-to-Risk order identity policy. */
  public OrderSessionRegistry() {
    this(new RiskOrderIdentityDeriver());
  }

  /** Creates a registry using the supplied canonical order identity derivation. */
  public OrderSessionRegistry(RiskOrderIdentityDeriver orderIdentityDeriver) {
    this.orderIdentityDeriver =
        Objects.requireNonNull(orderIdentityDeriver, "orderIdentityDeriver");
  }

  /** Registers a newly admitted order with the originating FIX session. */
  public void registerAcceptedOrder(SessionID sessionId, WalRecord walRecord, char ordStatus) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(walRecord, "walRecord");
    final String canonicalOrderId = orderIdentityDeriver.derive(walRecord);
    identityIndex.register(sessionId, walRecord.orderId(), canonicalOrderId);
    admittedOrdersByCanonicalOrderId.putIfAbsent(canonicalOrderId, walRecord);
    statesByCanonicalOrderId.put(
        canonicalOrderId,
        new OrderSessionState(
            sessionId,
            walRecord.accountId(),
            FixOrderSnapshot.from(walRecord),
            new OrderSessionLifecycle(ordStatus)));
  }

  /** Returns the first accepted command for one session-scoped FIX order identity. */
  public Optional<WalRecord> findAdmittedOrder(SessionID sessionId, String orderId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(orderId, "orderId");
    final WalRecord canonical = admittedOrdersByCanonicalOrderId.get(orderId);
    if (canonical != null) {
      return Optional.of(canonical);
    }
    return identityIndex
        .resolve(sessionId, orderId)
        .map(admittedOrdersByCanonicalOrderId::get);
  }

  /** Registers a cancel request and retains its client correlation identifiers. */
  public void registerCancelRequest(SessionID sessionId, WalRecord walRecord) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(walRecord, "walRecord");
    final String canonicalOrderId = orderIdentityDeriver.derive(walRecord);
    identityIndex.register(sessionId, walRecord.orderId(), canonicalOrderId);
    statesByCanonicalOrderId.compute(
        canonicalOrderId,
        (ignored, existing) -> {
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

  /** Finds the session state for one session-scoped FIX order identity. */
  public Optional<OrderSessionState> find(SessionID sessionId, String orderId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(orderId, "orderId");
    final OrderSessionState canonical = statesByCanonicalOrderId.get(orderId);
    if (canonical != null) {
      return Optional.of(canonical);
    }
    return identityIndex.resolve(sessionId, orderId).map(statesByCanonicalOrderId::get);
  }

  /** Finds session state by canonical order identity or one unambiguous FIX-facing identity. */
  public Optional<OrderSessionState> find(String orderId) {
    Objects.requireNonNull(orderId, "orderId");
    final OrderSessionState canonical = statesByCanonicalOrderId.get(orderId);
    if (canonical != null) {
      return Optional.of(canonical);
    }
    return identityIndex.resolveUnambiguous(orderId).map(statesByCanonicalOrderId::get);
  }

  @Override
  public Optional<SessionID> resolveSessionId(ExecutionEvent executionEvent) {
    return find(executionEvent.getOrderId()).map(OrderSessionState::sessionId);
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

  /** Records a successfully delivered execution and its resulting local lifecycle. */
  public void recordExecution(ExecutionEvent executionEvent) {
    final String orderId = executionEvent.getOrderId();
    final Optional<String> canonicalOrderId =
        statesByCanonicalOrderId.containsKey(orderId)
            ? Optional.of(orderId)
            : identityIndex.resolveUnambiguous(orderId);
    canonicalOrderId.ifPresent(
        resolvedOrderId ->
            statesByCanonicalOrderId.computeIfPresent(
                resolvedOrderId,
                (ignored, state) -> {
                  if (!state.lifecycle().accepts(executionEvent.getExecutionType())) {
                    return state;
                  }
                  return state.withLifecycle(
                      state.lifecycle().after(executionEvent.getExecutionType()));
                }));
    seenExecIds.add(executionEvent.getExecId());
  }

  /**
   * Records a final Matching Event lifecycle transition after its durable FIX intent was sent.
   *
   * <p>The registry is only an ingress-session cache; the delivery ledger remains the restart-safe
   * authority. Updating the cache keeps later cancel acknowledgement status aligned with the last
   * client-visible report.
   */
  public void recordFinalOrderStatus(String orderId, char orderStatus) {
    Objects.requireNonNull(orderId, "orderId");
    final Optional<String> canonicalOrderId =
        statesByCanonicalOrderId.containsKey(orderId)
            ? Optional.of(orderId)
            : identityIndex.resolveUnambiguous(orderId);
    canonicalOrderId.ifPresent(
        resolvedOrderId ->
            statesByCanonicalOrderId.computeIfPresent(
                resolvedOrderId,
                (ignored, state) ->
                    state.withLifecycle(
                        state
                            .lifecycle()
                            .withCurrentOrdStatus(orderStatus)
                            .withLastCancelRequest(null))));
  }

  private static final class OrderIdentityIndex {
    private final ConcurrentHashMap<FixOrderKey, String> canonicalOrderIdByFixOrder =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> canonicalOrderIdsByFixOrderId =
        new ConcurrentHashMap<>();

    void register(SessionID sessionId, String fixOrderId, String canonicalOrderId) {
      final FixOrderKey key = new FixOrderKey(sessionId.toString(), fixOrderId);
      final String previous = canonicalOrderIdByFixOrder.putIfAbsent(key, canonicalOrderId);
      if (previous != null && !previous.equals(canonicalOrderId)) {
        throw new IllegalStateException("FIX order identity maps to multiple canonical orders");
      }
      canonicalOrderIdsByFixOrderId
          .computeIfAbsent(fixOrderId, ignored -> ConcurrentHashMap.newKeySet())
          .add(canonicalOrderId);
    }

    Optional<String> resolve(SessionID sessionId, String orderId) {
      return Optional.ofNullable(
          canonicalOrderIdByFixOrder.get(new FixOrderKey(sessionId.toString(), orderId)));
    }

    Optional<String> resolveUnambiguous(String orderId) {
      final Set<String> candidates = canonicalOrderIdsByFixOrderId.get(orderId);
      if (candidates == null || candidates.size() != 1) {
        return Optional.empty();
      }
      return candidates.stream().findFirst();
    }
  }

  private record FixOrderKey(String sessionId, String orderId) {}
}
