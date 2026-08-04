package com.simplematch.quickfixgateway.fix;

import com.simplematch.quickfixgateway.wal.WalRecord;
import java.time.Clock;
import java.time.Instant;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ClOrdID;

/** Validates and normalizes one inbound FIX NewOrderSingle before it can become durable. */
final class NewOrderCommandPreparer {
  private final CommandIdGenerator commandIdGenerator;
  private final Clock clock;

  NewOrderCommandPreparer(CommandIdGenerator commandIdGenerator, Clock clock) {
    this.commandIdGenerator = commandIdGenerator;
    this.clock = clock;
  }

  /**
   * Prepares a validated new-order record or describes the protocol rejection to render.
   *
   * @param message inbound FIX NewOrderSingle message
   * @param sessionId originating FIX session
   * @return the normalized record and timestamp used by the rest of the durable path
   * @throws NewOrderPreparationFailure when identity or normalized order fields are invalid
   */
  PreparedNewOrder prepare(Message message, SessionID sessionId)
      throws NewOrderPreparationFailure {
    final Instant now = Instant.now(clock);
    try {
      final String clOrdId = FixInboundFieldValues.optionalString(message, ClOrdID.FIELD);
      final FixInboundIdentity identity =
          FixInboundIdentityValidator.validateNewOrder(sessionId, clOrdId);
      if (!identity.valid()) {
        throw new NewOrderPreparationFailure(identity.failure(), now);
      }
      final WalRecord walRecord =
          FixInboundCommandFactory.newOrder(
              message, identity, commandIdGenerator.nextCommandId(), now);
      return new PreparedNewOrder(walRecord, now);
    } catch (NewOrderPreparationFailure failure) {
      throw failure;
    } catch (FieldNotFound | IllegalArgumentException failure) {
      throw new NewOrderPreparationFailure(
          FixInboundValidationFailure.fromException("INVALID_NEW_ORDER", failure), now, failure);
    }
  }
}
