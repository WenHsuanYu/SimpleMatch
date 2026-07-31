package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Shared JDBC mechanics for the account-authority reader and lifecycle-writer adapters. */
final class AccountAuthorityJdbcSupport {
  static final int LOCK_QUERY_TIMEOUT_SECONDS = 2;
  static final RowMapper<AccountLimit> LIMIT_MAPPER =
      (rs, row) ->
          new AccountLimit(
              rs.getString("account_id"),
              rs.getObject("trading_day", LocalDate.class),
              rs.getString("currency"),
              rs.getBigDecimal("limit_total_notional"),
              rs.getBigDecimal("reserved_notional"),
              rs.getBigDecimal("utilized_notional"),
              rs.getBigDecimal("available_notional"),
              rs.getLong("version"),
              rs.getLong("updated_at_unix_ms"));
  static final RowMapper<AccountPosition> POSITION_MAPPER =
      (rs, row) ->
          new AccountPosition(
              rs.getString("account_id"),
              rs.getString("symbol"),
              rs.getBigDecimal("long_qty"),
              rs.getBigDecimal("short_qty"),
              rs.getBigDecimal("reserved_long_qty"),
              rs.getBigDecimal("reserved_short_qty"),
              rs.getLong("version"),
              rs.getLong("updated_at_unix_ms"));
  static final RowMapper<AccountReservation> RESERVATION_MAPPER =
      (rs, row) ->
          new AccountReservation(
              rs.getString("reservation_id"),
              rs.getString("request_id"),
              rs.getString("order_id"),
              rs.getString("account_id"),
              rs.getString("symbol"),
              Side.valueOf(rs.getString("side")),
              rs.getBigDecimal("quantity"),
              rs.getBigDecimal("remaining_quantity"),
              rs.getBigDecimal("filled_quantity"),
              rs.getBigDecimal("limit_price"),
              rs.getBigDecimal("reserved_notional"),
              ReservationStatus.valueOf(rs.getString("status")),
              rs.getString("reason_code"),
              rs.getString("reason_text"),
              rs.getLong("version"),
              rs.getLong("created_at_unix_ms"),
              rs.getLong("updated_at_unix_ms"));

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
                SELECT reservation_id, request_id, order_id, account_id, symbol, side, quantity, limit_price,
                  reserved_notional, status, reason_code, reason_text, created_at_unix_ms, updated_at_unix_ms,
                  remaining_quantity, filled_quantity, version
                FROM account_service.account_reservations
        """;
  }
}
