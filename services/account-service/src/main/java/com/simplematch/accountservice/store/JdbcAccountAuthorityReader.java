package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountReservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC adapter that reads authoritative account state for account-service use cases. */
@Repository
public class JdbcAccountAuthorityReader implements AccountAuthorityReader {
  private final AccountAuthorityJdbcSupport jdbc;

  /** Creates the reader with the service's transaction-participating JDBC template. */
  public JdbcAccountAuthorityReader(JdbcTemplate jdbcTemplate) {
    jdbc = new AccountAuthorityJdbcSupport(jdbcTemplate);
  }

  @Override
  public Optional<AccountLimit> findLimitForUpdate(String accountId, LocalDate tradingDay) {
    return jdbc.queryForUpdate(
        """
                        SELECT account_id, trading_day, currency, limit_total_notional, reserved_notional,
                          utilized_notional, available_notional, version, updated_at_unix_ms
                        FROM account_service.account_limits
                        WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' AND trading_day = ?
                        FOR UPDATE
        """,
        AccountAuthorityJdbcSupport.LIMIT_MAPPER,
        accountId,
        tradingDay);
  }

  @Override
  public Optional<AccountLimit> findLimit(String accountId, LocalDate tradingDay) {
    return jdbc.queryFirst(
        """
                        SELECT account_id, trading_day, currency, limit_total_notional, reserved_notional,
                          utilized_notional, available_notional, version, updated_at_unix_ms
                        FROM account_service.account_limits
                        WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' AND trading_day = ?
        """,
        AccountAuthorityJdbcSupport.LIMIT_MAPPER,
        accountId,
        tradingDay);
  }

  @Override
  public Optional<AccountPosition> findPositionForUpdate(String accountId, String symbol) {
    return jdbc.queryForUpdate(
        """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? AND symbol = ?
                        FOR UPDATE
        """,
        AccountAuthorityJdbcSupport.POSITION_MAPPER,
        accountId,
        symbol);
  }

  @Override
  public Optional<AccountPosition> findPosition(String accountId, String symbol) {
    return jdbc.queryFirst(
        """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? AND symbol = ?
        """,
        AccountAuthorityJdbcSupport.POSITION_MAPPER,
        accountId,
        symbol);
  }

  @Override
  public List<AccountPosition> findPositions(String accountId) {
    return jdbc.query(
        """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? ORDER BY symbol
        """,
        AccountAuthorityJdbcSupport.POSITION_MAPPER,
        accountId);
  }

  @Override
  public Optional<AccountReservation> findReservationByRequestId(String requestId) {
    return jdbc.queryFirst(
        jdbc.reservationSelect() + " WHERE request_id = ?",
        AccountAuthorityJdbcSupport.RESERVATION_MAPPER,
        requestId);
  }

  @Override
  public Optional<AccountReservation> findReservationForUpdate(String reservationId) {
    return jdbc.queryForUpdate(
        jdbc.reservationSelect() + " WHERE reservation_id = ? FOR UPDATE",
        AccountAuthorityJdbcSupport.RESERVATION_MAPPER,
        reservationId);
  }
}
