package com.simplematch.riskservice.store;

import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.SubmissionBusinessKey;
import com.simplematch.riskservice.submission.SubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcSubmissionRepository implements SubmissionRepository {
    private static final RowMapper<SubmissionResult> SUBMISSION_ROW_MAPPER = (resultSet, rowNum) -> new SubmissionResult(
        resultSet.getString("request_id"),
        resultSet.getString("sender_comp_id"),
        resultSet.getString("target_comp_id"),
        resultSet.getObject("trading_day", LocalDate.class),
        resultSet.getString("order_id"),
        resultSet.getString("raw_cl_ord_id"),
        resultSet.getString("raw_orig_cl_ord_id"),
        CommandType.valueOf(resultSet.getString("command_type")),
        resultSet.getBoolean("accepted"),
        resultSet.getString("reason_code"),
        resultSet.getString("reason_text"),
        resultSet.getLong("created_at_unix_ms"),
        resultSet.getString("cl_ord_id"),
        resultSet.getString("orig_cl_ord_id"),
        resultSet.getBoolean("business_key_surrogated"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

  @Override
  public Optional<SubmissionResult> findByBusinessKey(SubmissionBusinessKey businessKey) {
    return jdbcTemplate.query(
        """
        SELECT request_id, sender_comp_id, target_comp_id, trading_day, order_id, cl_ord_id, orig_cl_ord_id,
          raw_cl_ord_id, raw_orig_cl_ord_id, command_type, accepted, reason_code, reason_text,
          business_key_surrogated,
          created_at_unix_ms
        FROM risk_service.risk_submissions
        WHERE sender_comp_id = ?
            AND target_comp_id = ?
            AND trading_day = ?
            AND command_type = ?
            AND cl_ord_id = ?
            AND business_key_surrogated = ?
        """,
        SUBMISSION_ROW_MAPPER,
        businessKey.senderCompId(),
        businessKey.targetCompId(),
        businessKey.tradingDay(),
        businessKey.commandType().name(),
        businessKey.clOrdId(),
        businessKey.businessKeySurrogated())
        .stream()
        .findFirst();
  }

  @Override
  public void insert(SubmissionResult submission, String outboxEventId) {
    jdbcTemplate.update(
        """
        INSERT INTO risk_service.risk_submissions (
            request_id,
            sender_comp_id,
            target_comp_id,
            trading_day,
            order_id,
            cl_ord_id,
            orig_cl_ord_id,
            raw_cl_ord_id,
            raw_orig_cl_ord_id,
            command_type,
            accepted,
            reason_code,
            reason_text,
            business_key_surrogated,
            created_at_unix_ms,
            outbox_event_id
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        submission.requestId(),
        submission.senderCompId(),
        submission.targetCompId(),
        submission.tradingDay(),
        submission.orderId(),
          submission.persistedClOrdId(),
          submission.persistedOrigClOrdId(),
          submission.clOrdId(),
          submission.origClOrdId(),
        submission.commandType().name(),
        submission.accepted(),
        submission.reasonCode(),
        submission.reasonText(),
        submission.businessKeySurrogated(),
        submission.createdAtUnixMs(),
        UUID.fromString(outboxEventId));
  }
}