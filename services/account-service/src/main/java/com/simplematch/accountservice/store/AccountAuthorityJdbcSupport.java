package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountId;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountLimitIdentity;
import com.simplematch.accountservice.authority.AccountLimitLedger;
import com.simplematch.accountservice.authority.AccountLimitRevision;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountPositionIdentity;
import com.simplematch.accountservice.authority.AccountPositionInventory;
import com.simplematch.accountservice.authority.AccountPositionRevision;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.accountservice.authority.ReservationAllocation;
import com.simplematch.accountservice.authority.ReservationLifecycle;
import com.simplematch.accountservice.authority.ReservationOutcome;
import com.simplematch.accountservice.authority.ReservationOwnership;
import com.simplematch.accountservice.authority.ReservationRevision;
import com.simplematch.accountservice.reservation.ReservationIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Shared JDBC mechanics for the account-authority reader and lifecycle-writer adapters. */
final class AccountAuthorityJdbcSupport {
  static final int LOCK_QUERY_TIMEOUT_SECONDS = 2;
  static final RowMapper<AccountLimit> LIMIT_MAPPER =
      (rs, row) ->
          new AccountLimit(
              new AccountLimitIdentity(
                  accountId(rs),
                  rs.getObject("trading_day", LocalDate.class),
                  rs.getString("currency")),
              new AccountLimitLedger(
                  rs.getBigDecimal("limit_total_notional"),
                  rs.getBigDecimal("reserved_notional"),
                  rs.getBigDecimal("utilized_notional"),
                  rs.getBigDecimal("available_notional")),
              new AccountLimitRevision(rs.getLong("version"), rs.getLong("updated_at_unix_ms")));
  static final RowMapper<AccountPosition> POSITION_MAPPER =
      (rs, row) ->
          new AccountPosition(
              new AccountPositionIdentity(accountId(rs), rs.getString("symbol")),
              new AccountPositionInventory(
                  rs.getBigDecimal("long_qty"),
                  rs.getBigDecimal("short_qty"),
                  rs.getBigDecimal("reserved_long_qty"),
                  rs.getBigDecimal("reserved_short_qty")),
              new AccountPositionRevision(rs.getLong("version"), rs.getLong("updated_at_unix_ms")));
  static final RowMapper<AccountReservation> RESERVATION_MAPPER =
      (rs, row) -> reservation(rs);

  private static AccountId accountId(ResultSet rs) throws java.sql.SQLException {
    return new AccountId(rs.getObject("account_id", UUID.class));
  }

  private static AccountReservation reservation(ResultSet rs) throws java.sql.SQLException {
    return new AccountReservation(
        new ReservationIdentity(
            new ReservationIdentity.RequestId(rs.getString("request_id")),
            new ReservationIdentity.ReservationId(rs.getString("reservation_id")),
            new ReservationIdentity.OrderId(rs.getString("order_id"))),
        new ReservationOwnership(accountId(rs)),
        new ReservationTerms(
            new ReservationTerms.InstrumentSymbol(rs.getString("symbol")),
            new ReservationTerms.VenueMic(rs.getString("venue_mic")),
            Side.valueOf(rs.getString("side")),
            new ReservationTerms.ReservationQuantity(rs.getBigDecimal("quantity")),
            new ReservationTerms.LimitPrice(rs.getBigDecimal("limit_price"))),
        new ReservationLifecycle(
            new ReservationAllocation(
                rs.getBigDecimal("remaining_quantity"),
                rs.getBigDecimal("filled_quantity"),
                rs.getBigDecimal("reserved_notional")),
            new ReservationOutcome(
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getString("reason_code"),
                rs.getString("reason_text")),
            new ReservationRevision(
                rs.getLong("version"),
                rs.getLong("created_at_unix_ms"),
                rs.getLong("updated_at_unix_ms"))));
  }

  private final JdbcTemplate jdbcTemplate;

  AccountAuthorityJdbcSupport(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  <T> Optional<T> queryFirst(String sql, RowMapper<T> mapper, Object... arguments) {
    return jdbcTemplate.query(sql, mapper, arguments).stream().findFirst();
  }

  <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
    return jdbcTemplate.query(sql, mapper, arguments);
  }

  <T> Optional<T> queryForUpdate(String sql, RowMapper<T> mapper, Object... arguments) {
    return jdbcTemplate.execute(
        (ConnectionCallback<Optional<T>>)
            connection -> {
              try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < arguments.length; index++) {
                  statement.setObject(index + 1, arguments[index]);
                }
                statement.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
                try (ResultSet rows = statement.executeQuery()) {
                  return rows.next() ? Optional.of(mapper.mapRow(rows, 0)) : Optional.empty();
                }
              }
            });
  }

  <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  boolean isPostgres() {
    return Objects.requireNonNull(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")),
        "database product name");
  }

  String reservationSelect() {
    return
        """
                SELECT reservation_id, request_id, order_id, account_id, symbol, venue_mic, side, quantity, limit_price,
                  reserved_notional, status, reason_code, reason_text, created_at_unix_ms, updated_at_unix_ms,
                  remaining_quantity, filled_quantity, version
                FROM account_service.account_reservations
        """;
  }
}
