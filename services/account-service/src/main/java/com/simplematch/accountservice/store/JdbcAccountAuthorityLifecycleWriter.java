package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.reservation.AccountReservationInvariantException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC adapter that persists account-authority lifecycle and idempotency state. */
@Repository
public class JdbcAccountAuthorityLifecycleWriter implements AccountAuthorityLifecycleWriter {
  private final AccountAuthorityJdbcSupport jdbc;

  /** Creates the lifecycle writer with the service's transaction-participating JDBC template. */
  public JdbcAccountAuthorityLifecycleWriter(JdbcTemplate jdbcTemplate) {
    jdbc = new AccountAuthorityJdbcSupport(jdbcTemplate);
  }

  @Override
  public void claimReservationRequest(String requestId, long claimedAtUnixMs) {
    final String insert =
        jdbc.isPostgres()
            ? "INSERT INTO account_service.reservation_request_locks "
                + "(request_id, created_at_unix_ms) VALUES (?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING"
            : "MERGE INTO account_service.reservation_request_locks "
                + "(request_id, created_at_unix_ms) KEY(request_id) VALUES (?, ?)";
    jdbc.update(insert, requestId, claimedAtUnixMs);
    jdbc.queryForUpdate(
        "SELECT request_id FROM account_service.reservation_request_locks "
            + "WHERE request_id = ? FOR UPDATE",
        (rows, row) -> rows.getString("request_id"),
        requestId);
  }

  @Override
  public void insertLimit(AccountLimit limit) {
    jdbc.update(
        """
                        INSERT INTO account_service.account_limits (
                          account_id, scope_type, scope_key, trading_day, currency, limit_total_notional,
                          reserved_notional, utilized_notional, available_notional, version, updated_at_unix_ms)
                        VALUES (?, 'ACCOUNT', '*', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        limit.accountIdentity().value(),
        limit.tradingDay(),
        limit.currency(),
        limit.limitTotalNotional(),
        limit.reservedNotional(),
        limit.utilizedNotional(),
        limit.availableNotional(),
        limit.version(),
        limit.updatedAtUnixMs());
  }

  @Override
  public void insertPosition(AccountPosition position) {
    jdbc.update(
        """
                        INSERT INTO account_service.account_positions (
                          account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        position.accountIdentity().value(),
        position.symbol(),
        position.longQuantity(),
        position.shortQuantity(),
        position.reservedLongQuantity(),
        position.reservedShortQuantity(),
        position.version(),
        position.updatedAtUnixMs());
  }

  @Override
  public void insertReservation(AccountReservation reservation) {
    jdbc.update(
        """
                        INSERT INTO account_service.account_reservations (
                          reservation_id, request_id, order_id, account_id, symbol, venue_mic, side, quantity, limit_price,
                          reserved_notional, status, reason_code, reason_text, created_at_unix_ms, updated_at_unix_ms,
                          remaining_quantity, filled_quantity, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        reservation.reservationId(),
        reservation.requestId(),
        reservation.orderId(),
        reservation.accountIdentity().value(),
        reservation.symbol(),
        reservation.venueMic(),
        reservation.side().name(),
        reservation.quantity(),
        reservation.limitPrice(),
        reservation.reservedNotional(),
        reservation.status().name(),
        reservation.reasonCode(),
        reservation.reasonText(),
        reservation.createdAtUnixMs(),
        reservation.updatedAtUnixMs(),
        reservation.remainingQuantity(),
        reservation.filledQuantity(),
        reservation.version());
  }

  @Override
  public void updateLimit(AccountLimit limit, long expectedVersion) {
    updateExactlyOne(
        "UPDATE account_service.account_limits SET reserved_notional = ?, utilized_notional = ?, "
            + "available_notional = ?, version = ?, updated_at_unix_ms = ? "
            + "WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' "
            + "AND trading_day = ? AND version = ?",
        limit.reservedNotional(),
        limit.utilizedNotional(),
        limit.availableNotional(),
        limit.version(),
        limit.updatedAtUnixMs(),
        limit.accountIdentity().value(),
        limit.tradingDay(),
        expectedVersion);
  }

  @Override
  public void updatePosition(AccountPosition position, long expectedVersion) {
    updateExactlyOne(
        "UPDATE account_service.account_positions SET long_qty = ?, short_qty = ?, "
            + "reserved_long_qty = ?, reserved_short_qty = ?, version = ?, updated_at_unix_ms = ? "
            + "WHERE account_id = ? AND symbol = ? AND version = ?",
        position.longQuantity(),
        position.shortQuantity(),
        position.reservedLongQuantity(),
        position.reservedShortQuantity(),
        position.version(),
        position.updatedAtUnixMs(),
        position.accountIdentity().value(),
        position.symbol(),
        expectedVersion);
  }

  @Override
  public void updateReservation(AccountReservation reservation, long expectedVersion) {
    updateExactlyOne(
        "UPDATE account_service.account_reservations "
            + "SET remaining_quantity = ?, filled_quantity = ?, reserved_notional = ?, status = ?, "
            + "reason_code = ?, reason_text = ?, version = ?, updated_at_unix_ms = ? "
            + "WHERE reservation_id = ? AND version = ?",
        reservation.remainingQuantity(),
        reservation.filledQuantity(),
        reservation.reservedNotional(),
        reservation.status().name(),
        reservation.reasonCode(),
        reservation.reasonText(),
        reservation.version(),
        reservation.updatedAtUnixMs(),
        reservation.reservationId(),
        expectedVersion);
  }

  @Override
  public boolean claimInbox(
      String consumerName,
      String eventId,
      String aggregateId,
      Long aggregateSequence,
      long receivedAt) {
    final Integer inboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM account_service.inbox WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            consumerName,
            UUID.fromString(eventId));
    if (!jdbc.isPostgres() && inboxCount != null && inboxCount > 0) {
      return false;
    }
    final String insert =
        jdbc.isPostgres()
            ? "INSERT INTO account_service.inbox "
                + "(consumer_name, event_id, aggregate_id, aggregate_sequence, "
                + "received_at_unix_ms) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
            : "MERGE INTO account_service.inbox "
                + "(consumer_name, event_id, aggregate_id, aggregate_sequence, "
                + "received_at_unix_ms) KEY(consumer_name, event_id) VALUES (?, ?, ?, ?, ?)";
    try {
      return jdbc.update(
              insert,
              consumerName,
              UUID.fromString(eventId),
              aggregateId,
              aggregateSequence,
              receivedAt)
          == 1;
    } catch (DuplicateKeyException duplicate) {
      return false;
    }
  }

  private void updateExactlyOne(String sql, Object... arguments) {
    if (jdbc.update(sql, arguments) != 1) {
      throw new AccountReservationInvariantException(
          "account authority optimistic version conflict");
    }
  }
}
