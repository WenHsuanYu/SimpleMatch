package com.simplematch.riskservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.riskservice.submission.CommandType;
import com.simplematch.riskservice.submission.SubmissionRepository;
import com.simplematch.riskservice.submission.SubmissionResult;
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
    dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
  void insertsAndFindsSubmissionByIdempotencyKey() {
    final SubmissionResult submission = acceptedSubmission();

    repository.insert(submission, "outbox-1");

    assertThat(repository.findByIdempotencyKey(submission.idempotencyKey())).contains(submission);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT outbox_event_id FROM risk_submissions WHERE idempotency_key = ?",
        String.class,
        submission.idempotencyKey())).isEqualTo("outbox-1");
  }

  @Test
  void returnsEmptyWhenIdempotencyKeyDoesNotExist() {
    assertThat(repository.findByIdempotencyKey("missing")).isEmpty();
  }

  @Test
  void throwsDuplicateKeyWhenIdempotencyKeyAlreadyExists() {
    final SubmissionResult submission = acceptedSubmission();
    repository.insert(submission, "outbox-1");

    assertThatThrownBy(() -> repository.insert(submission, "outbox-2"))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
  }

  private SubmissionResult acceptedSubmission() {
    return new SubmissionResult(
        "COMMAND_TYPE_CANCEL|CXL-1",
        "cmd-1",
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