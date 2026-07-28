package com.simplematch.accountservice.store;

import com.simplematch.accountservice.authority.AccountAuthorityRepository;
import com.simplematch.accountservice.authority.AccountLimit;
import com.simplematch.accountservice.authority.AccountPosition;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin JDBC adapter for account limits, positions, reservations, and inbox claims.
 */
@Repository
@SuppressWarnings("PMD.TooManyMethods") // One JDBC adapter implements the authoritative-account port.
public class JdbcAccountAuthorityRepository implements AccountAuthorityRepository {
    private static final int LOCK_QUERY_TIMEOUT_SECONDS = 2;
    private static final RowMapper<AccountLimit> LIMIT_MAPPER = (rs, row) -> new AccountLimit(
            rs.getString("account_id"), rs.getObject("trading_day", LocalDate.class), rs.getString("currency"),
            rs.getBigDecimal("limit_total_notional"), rs.getBigDecimal("reserved_notional"),
            rs.getBigDecimal("utilized_notional"), rs.getBigDecimal("available_notional"),
            rs.getLong("version"), rs.getLong("updated_at_unix_ms"));
    private static final RowMapper<AccountPosition> POSITION_MAPPER = (rs, row) -> new AccountPosition(
            rs.getString("account_id"), rs.getString("symbol"), rs.getBigDecimal("long_qty"),
            rs.getBigDecimal("short_qty"), rs.getBigDecimal("reserved_long_qty"),
            rs.getBigDecimal("reserved_short_qty"), rs.getLong("version"), rs.getLong("updated_at_unix_ms"));
    private static final RowMapper<AccountReservation> RESERVATION_MAPPER = (rs, row) -> new AccountReservation(
            rs.getString("reservation_id"), rs.getString("request_id"), rs.getString("order_id"),
            rs.getString("account_id"), rs.getString("symbol"), Side.valueOf(rs.getString("side")),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("remaining_quantity"),
            rs.getBigDecimal("filled_quantity"), rs.getBigDecimal("limit_price"),
            rs.getBigDecimal("reserved_notional"), ReservationStatus.valueOf(rs.getString("status")),
            rs.getString("reason_code"), rs.getString("reason_text"), rs.getLong("version"),
            rs.getLong("created_at_unix_ms"), rs.getLong("updated_at_unix_ms"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the repository with the account-service datasource.
     */
    public JdbcAccountAuthorityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<AccountLimit> findLimitForUpdate(String accountId, LocalDate tradingDay) {
        return queryForUpdate(
                """
                        SELECT account_id, trading_day, currency, limit_total_notional, reserved_notional,
                          utilized_notional, available_notional, version, updated_at_unix_ms
                        FROM account_service.account_limits
                        WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' AND trading_day = ?
                        FOR UPDATE
                        """, LIMIT_MAPPER, accountId, tradingDay);
    }

    @Override
    public Optional<AccountLimit> findLimit(String accountId, LocalDate tradingDay) {
        return queryForUpdate(
                """
                        SELECT account_id, trading_day, currency, limit_total_notional, reserved_notional,
                          utilized_notional, available_notional, version, updated_at_unix_ms
                        FROM account_service.account_limits
                        WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' AND trading_day = ?
                        """, LIMIT_MAPPER, accountId, tradingDay).stream().findFirst();
    }

    @Override
    public Optional<AccountPosition> findPositionForUpdate(String accountId, String symbol) {
        return queryForUpdate(
                """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? AND symbol = ?
                        FOR UPDATE
                        """, POSITION_MAPPER, accountId, symbol);
    }

    @Override
    public Optional<AccountPosition> findPosition(String accountId, String symbol) {
        return jdbcTemplate.query(
                """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? AND symbol = ?
                        """, POSITION_MAPPER, accountId, symbol).stream().findFirst();
    }

    @Override
    public List<AccountPosition> findPositions(String accountId) {
        return jdbcTemplate.query(
                """
                        SELECT account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms
                        FROM account_service.account_positions
                        WHERE account_id = ? ORDER BY symbol
                        """, POSITION_MAPPER, accountId);
    }

    @Override
    public Optional<AccountReservation> findReservationByRequestId(String requestId) {
        return jdbcTemplate.query(reservationSelect() + " WHERE request_id = ?", RESERVATION_MAPPER, requestId)
                .stream().findFirst();
    }

    @Override
    public void claimReservationRequest(String requestId, long claimedAtUnixMs) {
        final String insert = isPostgres()
                ? "INSERT INTO account_service.reservation_request_locks (request_id, created_at_unix_ms) VALUES (?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING"
                : "MERGE INTO account_service.reservation_request_locks (request_id, created_at_unix_ms) KEY(request_id) VALUES (?, ?)";
        jdbcTemplate.update(insert, requestId, claimedAtUnixMs);
        queryForUpdate("SELECT request_id FROM account_service.reservation_request_locks WHERE request_id = ? FOR UPDATE",
                (rows, row) -> rows.getString("request_id"), requestId);
    }

    @Override
    public Optional<AccountReservation> findReservationForUpdate(String reservationId) {
        return queryForUpdate(reservationSelect() + " WHERE reservation_id = ? FOR UPDATE", RESERVATION_MAPPER,
                reservationId);
    }

    @Override
    public void insertLimit(AccountLimit limit) {
        jdbcTemplate.update(
                """
                        INSERT INTO account_service.account_limits (
                          account_id, scope_type, scope_key, trading_day, currency, limit_total_notional,
                          reserved_notional, utilized_notional, available_notional, version, updated_at_unix_ms)
                        VALUES (?, 'ACCOUNT', '*', ?, ?, ?, ?, ?, ?, ?, ?)
                        """, limit.accountId(), limit.tradingDay(), limit.currency(), limit.limitTotalNotional(),
                limit.reservedNotional(), limit.utilizedNotional(), limit.availableNotional(), limit.version(),
                limit.updatedAtUnixMs());
    }

    @Override
    public void insertPosition(AccountPosition position) {
        jdbcTemplate.update(
                """
                        INSERT INTO account_service.account_positions (
                          account_id, symbol, long_qty, short_qty, reserved_long_qty, reserved_short_qty,
                          version, updated_at_unix_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, position.accountId(), position.symbol(), position.longQuantity(), position.shortQuantity(),
                position.reservedLongQuantity(), position.reservedShortQuantity(), position.version(),
                position.updatedAtUnixMs());
    }

    @Override
    public void insertReservation(AccountReservation reservation) {
        jdbcTemplate.update(
                """
                        INSERT INTO account_service.account_reservations (
                          reservation_id, request_id, order_id, account_id, symbol, side, quantity, limit_price,
                          reserved_notional, status, reason_code, reason_text, created_at_unix_ms, updated_at_unix_ms,
                          remaining_quantity, filled_quantity, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, reservation.reservationId(), reservation.requestId(), reservation.orderId(), reservation.accountId(),
                reservation.symbol(), reservation.side().name(), reservation.quantity(), reservation.limitPrice(),
                reservation.reservedNotional(), reservation.status().name(), reservation.reasonCode(), reservation.reasonText(),
                reservation.createdAtUnixMs(), reservation.updatedAtUnixMs(), reservation.remainingQuantity(),
                reservation.filledQuantity(), reservation.version());
    }

    @Override
    public void updateLimit(AccountLimit limit, long expectedVersion) {
        update("UPDATE account_service.account_limits SET reserved_notional = ?, utilized_notional = ?, "
                        + "available_notional = ?, version = ?, updated_at_unix_ms = ? "
                        + "WHERE account_id = ? AND scope_type = 'ACCOUNT' AND scope_key = '*' AND trading_day = ? AND version = ?",
                limit.reservedNotional(), limit.utilizedNotional(), limit.availableNotional(), limit.version(),
                limit.updatedAtUnixMs(), limit.accountId(), limit.tradingDay(), expectedVersion);
    }

    @Override
    public void updatePosition(AccountPosition position, long expectedVersion) {
        update("UPDATE account_service.account_positions SET long_qty = ?, short_qty = ?, reserved_long_qty = ?, "
                        + "reserved_short_qty = ?, version = ?, updated_at_unix_ms = ? "
                        + "WHERE account_id = ? AND symbol = ? AND version = ?", position.longQuantity(), position.shortQuantity(),
                position.reservedLongQuantity(), position.reservedShortQuantity(), position.version(),
                position.updatedAtUnixMs(), position.accountId(), position.symbol(), expectedVersion);
    }

    @Override
    public void updateReservation(AccountReservation reservation, long expectedVersion) {
        update("UPDATE account_service.account_reservations SET remaining_quantity = ?, filled_quantity = ?, "
                        + "reserved_notional = ?, status = ?, reason_code = ?, reason_text = ?, version = ?, updated_at_unix_ms = ? "
                        + "WHERE reservation_id = ? AND version = ?", reservation.remainingQuantity(), reservation.filledQuantity(),
                reservation.reservedNotional(), reservation.status().name(), reservation.reasonCode(), reservation.reasonText(),
                reservation.version(), reservation.updatedAtUnixMs(), reservation.reservationId(), expectedVersion);
    }

    @Override
    public boolean claimInbox(String consumerName, String eventId, String aggregateId, Long aggregateSequence,
                              long receivedAt) {
        final boolean postgres = isPostgres();
        final Integer inboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.inbox WHERE consumer_name = ? AND event_id = ?", Integer.class,
                consumerName, UUID.fromString(eventId));
        if (!postgres && inboxCount != null && inboxCount > 0) {
            return false;
        }
        final String insert = postgres
                ? "INSERT INTO account_service.inbox (consumer_name, event_id, aggregate_id, aggregate_sequence, received_at_unix_ms) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
                : "MERGE INTO account_service.inbox (consumer_name, event_id, aggregate_id, aggregate_sequence, received_at_unix_ms) "
                + "KEY(consumer_name, event_id) VALUES (?, ?, ?, ?, ?)";
        return jdbcTemplate.update(
                insert, consumerName, UUID.fromString(eventId), aggregateId,
                aggregateSequence, receivedAt) == 1;
    }

    private void update(String sql, Object... arguments) {
        if (jdbcTemplate.update(sql, arguments) != 1) {
            throw new IllegalStateException("account authority optimistic version conflict");
        }
    }

    private <T> Optional<T> queryForUpdate(String sql, RowMapper<T> mapper, Object... arguments) {
        return jdbcTemplate.execute((ConnectionCallback<Optional<T>>) connection -> {
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

    private boolean isPostgres() {
        return Objects.requireNonNull(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")), "database product name");
    }

    private String reservationSelect() {
        return """
                SELECT reservation_id, request_id, order_id, account_id, symbol, side, quantity, limit_price,
                  reserved_notional, status, reason_code, reason_text, created_at_unix_ms, updated_at_unix_ms,
                  remaining_quantity, filled_quantity, version
                FROM account_service.account_reservations
                """;
    }
}
