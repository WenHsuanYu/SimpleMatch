package com.simplematch.accountservice.store;

import com.simplematch.accountservice.reservation.ReservationRecord;
import com.simplematch.accountservice.reservation.ReservationRepository;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed reservation repository for the account-service ingress table.
 */
@Repository
public class JdbcReservationRepository implements ReservationRepository {
  private static final RowMapper<ReservationRecord> RESERVATION_ROW_MAPPER = (resultSet, rowNum) ->
      new ReservationRecord(
          resultSet.getString("reservation_id"),
          resultSet.getString("request_id"),
          resultSet.getString("order_id"),
          resultSet.getString("account_id"),
          resultSet.getString("symbol"),
          Side.valueOf(resultSet.getString("side")),
          resultSet.getBigDecimal("quantity"),
          resultSet.getBigDecimal("limit_price"),
          resultSet.getBigDecimal("reserved_notional"),
          ReservationStatus.valueOf(resultSet.getString("status")),
          resultSet.getString("reason_code"),
          resultSet.getString("reason_text"),
          resultSet.getLong("created_at_unix_ms"),
          resultSet.getLong("updated_at_unix_ms"));

  private final JdbcTemplate jdbcTemplate;

  public JdbcReservationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
  }

  @Override
  public Optional<ReservationRecord> findByRequestId(String requestId) {
    return jdbcTemplate.query(
            """
                SELECT reservation_id, request_id, order_id, account_id, symbol, side, quantity,
                       limit_price, reserved_notional, status, reason_code, reason_text,
                       created_at_unix_ms, updated_at_unix_ms
                  FROM account_service.account_reservations
                 WHERE request_id = ?
                """,
            RESERVATION_ROW_MAPPER,
            requestId)
        .stream()
        .findFirst();
  }

  @Override
  public void insert(ReservationRecord reservation) {
    jdbcTemplate.update(
        """
            INSERT INTO account_service.account_reservations (
              reservation_id,
              request_id,
              order_id,
              account_id,
              symbol,
              side,
              quantity,
              limit_price,
              reserved_notional,
              status,
              reason_code,
              reason_text,
              created_at_unix_ms,
              updated_at_unix_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        reservation.reservationId(),
        reservation.requestId(),
        reservation.orderId(),
        reservation.accountId(),
        reservation.symbol(),
        reservation.side().name(),
        reservation.quantity(),
        reservation.limitPrice(),
        reservation.reservedNotional(),
        reservation.status().name(),
        reservation.reasonCode(),
        reservation.reasonText(),
        reservation.createdAtUnixMs(),
        reservation.updatedAtUnixMs());
  }
}