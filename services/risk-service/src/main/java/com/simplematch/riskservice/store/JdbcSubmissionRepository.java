package com.simplematch.riskservice.store;

import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.SubmissionBusinessKey;
import com.simplematch.riskservice.submission.SubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcSubmissionRepository implements SubmissionRepository {
  private static final RowMapper<SubmissionResult> SUBMISSION_ROW_MAPPER = (resultSet, rowNum) ->
      new SubmissionResult(
          resultSet.getString("request_id"),
          resultSet.getString("session_id"),
          resultSet.getObject("trading_day", LocalDate.class),
          resultSet.getString("order_id"),
          resultSet.getString("client_order_id"),
          resultSet.getString("original_client_order_id"),
          CommandType.valueOf(resultSet.getString("command_type")),
          resultSet.getBoolean("accepted"),
          resultSet.getString("reason_code"),
          resultSet.getString("reason_text"),
          resultSet.getLong("created_at_unix_ms"));

  private final JdbcTemplate jdbcTemplate;

  public JdbcSubmissionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
  }

  @Override
  public Optional<SubmissionResult> findByBusinessKey(SubmissionBusinessKey businessKey) {
    return jdbcTemplate.query(
            """
              SELECT request_id, session_id, trading_day, order_id, client_order_id, original_client_order_id,
                       command_type, accepted, reason_code, reason_text, created_at_unix_ms
                FROM risk_service.risk_submissions
                WHERE session_id = ?
                  AND trading_day = ?
                  AND command_type = ?
                  AND client_order_id = ?
                """,
            SUBMISSION_ROW_MAPPER,
            businessKey.sessionId(),
            businessKey.tradingDay(),
            businessKey.commandType().name(),
            businessKey.clientOrderId())
        .stream()
        .findFirst();
  }

  @Override
  public void insert(SubmissionResult submission, String outboxEventId) {
    jdbcTemplate.update(
        """
            INSERT INTO risk_service.risk_submissions (
              request_id,
              session_id,
              trading_day,
              order_id,
              client_order_id,
              original_client_order_id,
              command_type,
              accepted,
              reason_code,
              reason_text,
              created_at_unix_ms,
              outbox_event_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
        submission.requestId(),
        submission.sessionId(),
        submission.tradingDay(),
        submission.orderId(),
        submission.clientOrderId(),
        submission.originalClientOrderId(),
        submission.commandType().name(),
        submission.accepted(),
        submission.reasonCode(),
        submission.reasonText(),
        submission.createdAtUnixMs(),
        outboxEventId);
  }
}