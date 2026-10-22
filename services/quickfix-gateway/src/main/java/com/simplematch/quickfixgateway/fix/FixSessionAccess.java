package com.simplematch.quickfixgateway.fix;

import com.simplematch.contracts.v2.V2Identifiers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldNotFound;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.field.Account;
import quickfix.field.MsgType;
import quickfix.field.OrigClOrdID;
import quickfix.field.Password;
import quickfix.field.Username;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

/** Authenticates complete FIX sessions and authorizes new orders before live command admission. */
public final class FixSessionAccess {
  private static final Logger logger = LoggerFactory.getLogger(FixSessionAccess.class);
  private final Map<SessionID, FixCounterparty> counterparties;
  private final OrderSessionRegistry orderSessionRegistry;
  private final ConcurrentMap<SessionID, FixCounterparty> pending = new ConcurrentHashMap<>();
  private final ConcurrentMap<SessionID, FixCounterparty> authenticated = new ConcurrentHashMap<>();

  /**
   * Creates a deny-by-default policy from immutable provisioned counterparties.
   *
   * @param counterparties explicitly authorized peers; an empty list denies every Logon
   * @param orderSessionRegistry original-order context shared with durable cancellation handling
   * @throws IllegalArgumentException when the same complete session has multiple policies
   */
  public FixSessionAccess(
      List<FixCounterparty> counterparties, OrderSessionRegistry orderSessionRegistry) {
    this.orderSessionRegistry = java.util.Objects.requireNonNull(orderSessionRegistry);
    final Map<SessionID, FixCounterparty> policies = new HashMap<>();
    for (FixCounterparty counterparty : counterparties) {
      if (policies.putIfAbsent(counterparty.sessionId(), counterparty) != null) {
        throw new IllegalArgumentException("duplicate FIX counterparty session");
      }
    }
    this.counterparties = Map.copyOf(policies);
  }

  void authenticate(Message logon, SessionID sessionId) throws FieldNotFound, RejectLogon {
    logout(sessionId);
    final FixCounterparty counterparty = counterparties.get(sessionId);
    final String username = FixInboundFieldValues.optionalString(logon, Username.FIELD);
    final String password = FixInboundFieldValues.optionalString(logon, Password.FIELD);
    if (counterparty == null || !counterparty.authenticates(username, password)) {
      logger.warn("rejected FIX Logon authentication for session={}", sessionId);
      throw new RejectLogon("FIX authentication failed");
    }
    pending.put(sessionId, counterparty);
  }

  boolean logon(SessionID sessionId) {
    final FixCounterparty counterparty = pending.remove(sessionId);
    if (counterparty == null) {
      return false;
    }
    authenticated.put(sessionId, counterparty);
    return true;
  }

  /** Checks live identity and the same canonical account representation used by the WAL factory. */
  void authorize(Message message, SessionID sessionId) throws FieldNotFound, IncorrectTagValue {
    final FixCounterparty counterparty = authenticated.get(sessionId);
    if (counterparty == null) {
      logger.warn("rejected unauthenticated FIX application message for session={}", sessionId);
      throw new IncorrectTagValue(MsgType.FIELD, "FIX authentication required");
    }
    final UUID accountId;
    try {
      accountId = V2Identifiers.AccountId.parse(accountIdFor(message, sessionId)).value();
    } catch (IllegalArgumentException failure) {
      // The existing order validator renders malformed/missing Account as an ExecutionReport.
      return;
    }
    if (!counterparty.authorizes(accountId)) {
      logger.warn("rejected FIX account authorization for session={}", sessionId);
      throw new IncorrectTagValue(Account.FIELD, "FIX account is not authorized");
    }
  }

  /** Resolves cancellations from session-owned state so an omitted Account cannot bypass policy. */
  private String accountIdFor(Message message, SessionID sessionId) throws FieldNotFound {
    final String msgType = message.getHeader().getString(MsgType.FIELD);
    if (NewOrderSingle.MSGTYPE.equals(msgType)) {
      return FixInboundFieldValues.optionalString(message, Account.FIELD);
    }
    if (OrderCancelRequest.MSGTYPE.equals(msgType)) {
      final String originalId = FixInboundFieldValues.optionalString(message, OrigClOrdID.FIELD);
      if (!originalId.isBlank()) {
        return orderSessionRegistry
            .find(sessionId, FixInboundCommandFactory.orderIdFor(originalId))
            .map(OrderSessionState::accountId)
            .orElse("");
      }
    }
    // Existing handlers own malformed, unknown-order, and unsupported-message rejection semantics.
    return "";
  }

  void logout(SessionID sessionId) {
    pending.remove(sessionId);
    authenticated.remove(sessionId);
  }
}
