package com.simplematch.riskservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.SubmissionBusinessKey;
import com.simplematch.riskservice.submission.SubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionResult;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcSubmissionRepositoryTest {
  private JdbcTemplate jdbcTemplate;
  private SubmissionRepository repository;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
      "jdbc:h2:mem:"
        + UUID.randomUUID()
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS risk_service\\;SET SCHEMA risk_service");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Flyway.configure()
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .dataSource(dataSource)
        .locations("classpath:db/migration/risk-service")
        .load()
        .migrate();
    repository = new JdbcSubmissionRepository(jdbcTemplate);
  }

  @Test
  void insertsAndFindsSubmissionByBusinessKey() {
    final SubmissionResult submission = acceptedSubmission();

    repository.insert(submission, "outbox-1");

    assertThat(repository.findByBusinessKey(submission.businessKey())).contains(submission);
    assertThat(jdbcTemplate.queryForObject(
      "SELECT session_id FROM risk_submissions WHERE request_id = ?",
      String.class,
      submission.requestId())).isEqualTo("FIX.4.4:CLIENT->SIMPLEMATCH");
    assertThat(jdbcTemplate.queryForObject(
      "SELECT trading_day FROM risk_submissions WHERE request_id = ?",
      LocalDate.class,
      submission.requestId())).isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE request_id = ?",
        String.class,
        submission.requestId())).isEqualTo("outbox-1");
  }

  @Test
  void returnsEmptyWhenBusinessKeyDoesNotExist() {
    assertThat(repository.findByBusinessKey(new SubmissionBusinessKey(
        "FIX.4.4:CLIENT->OTHER",
        LocalDate.of(2024, 3, 27),
        CommandType.COMMAND_TYPE_CANCEL,
        "missing"))).isEmpty();
  }

  @Test
  void throwsDuplicateKeyWhenBusinessKeyAlreadyExists() {
    final SubmissionResult submission = acceptedSubmission();
    repository.insert(submission, "outbox-1");

    assertThatThrownBy(() -> repository.insert(submission, "outbox-2"))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
  }

  @Test
  void allowsSameClientOrderIdAcrossSessions() {
    final SubmissionResult first = acceptedSubmission();
    final SubmissionResult second = new SubmissionResult(
        "cmd-2",
        "FIX.4.4:CLIENT2->SIMPLEMATCH",
        first.tradingDay(),
        "O-C2",
        first.clientOrderId(),
        first.originalClientOrderId(),
        first.commandType(),
        first.accepted(),
        first.reasonCode(),
        first.reasonText(),
        101L);

    repository.insert(first, "outbox-1");
    repository.insert(second, "outbox-2");

    assertThat(countRows("risk_submissions")).isEqualTo(2);
    assertThat(repository.findByBusinessKey(second.businessKey())).contains(second);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM risk_submissions WHERE client_order_id = ?",
        Integer.class,
        first.clientOrderId())).isEqualTo(2);
  }

  private SubmissionResult acceptedSubmission() {
    return new SubmissionResult(
        "cmd-1",
        "FIX.4.4:CLIENT->SIMPLEMATCH",
        LocalDate.of(2024, 3, 27),
        "O-C1",
        "CXL-1",
        "C1",
        CommandType.COMMAND_TYPE_CANCEL,
        true,
        "",
        "",
        100L);
  }

  private int countRows(String tableName) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
  }
}