package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;
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
  private static final String OUTBOX_EVENT_ID_ONE = "00000000-0000-7000-8000-000000000011";
  private static final String OUTBOX_EVENT_ID_TWO = "00000000-0000-7000-8000-000000000012";

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
        .dataSource(dataSource)
        .locations("classpath:db/migration/risk-service")
        .load()
        .migrate();
    repository = new JdbcSubmissionRepository(jdbcTemplate);
  }

  @Test
  void insertsAndFindsSubmissionByBusinessKey() {
    final SubmissionResult submission = acceptedSubmission();

    repository.insert(submission, OUTBOX_EVENT_ID_ONE);

    assertThat(repository.findByBusinessKey(submission.businessKey())).contains(submission);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT sender_comp_id FROM risk_submissions WHERE request_id = ?",
                String.class,
                submission.requestId()))
        .isEqualTo("CLIENT");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT target_comp_id FROM risk_submissions WHERE request_id = ?",
                String.class,
                submission.requestId()))
        .isEqualTo("SIMPLEMATCH");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT trading_day FROM risk_submissions WHERE request_id = ?",
                LocalDate.class,
                submission.requestId()))
        .isEqualTo(LocalDate.of(2024, 3, 27));
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT outbox_event_id FROM risk_submissions WHERE request_id = ?",
                    UUID.class,
                    submission.requestId())
                .toString())
        .isEqualTo(OUTBOX_EVENT_ID_ONE);
  }

  @Test
  void returnsEmptyWhenBusinessKeyDoesNotExist() {
    assertThat(
            repository.findByBusinessKey(
                new SubmissionBusinessKey(
                    "CLIENT",
                    "OTHER",
                    LocalDate.of(2024, 3, 27),
                    CommandType.COMMAND_TYPE_CANCEL,
                    "missing",
                    false)))
        .isEmpty();
  }

  @Test
  void throwsDuplicateKeyWhenBusinessKeyAlreadyExists() {
    final SubmissionResult submission = acceptedSubmission();
    repository.insert(submission, OUTBOX_EVENT_ID_ONE);

    assertThatThrownBy(() -> repository.insert(submission, OUTBOX_EVENT_ID_TWO))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(countRows("risk_submissions")).isEqualTo(1);
  }

  @Test
  void allowsSameClientOrderIdAcrossSessions() {
    final SubmissionResult first = acceptedSubmission();
    final SubmissionResult second =
        new SubmissionResult(
            normalize("cmd-2"),
            "CLIENT2",
            "SIMPLEMATCH",
            first.tradingDay(),
            "O-C2",
            first.clOrdId(),
            first.origClOrdId(),
            first.commandType(),
            first.accepted(),
            first.reasonCode(),
            first.reasonText(),
            101L);

    repository.insert(first, OUTBOX_EVENT_ID_ONE);
    repository.insert(second, OUTBOX_EVENT_ID_TWO);

    assertThat(countRows("risk_submissions")).isEqualTo(2);
    assertThat(repository.findByBusinessKey(second.businessKey())).contains(second);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_submissions WHERE cl_ord_id = ?",
                Integer.class,
                first.clOrdId()))
        .isEqualTo(2);
  }

  @Test
  void storesSeparateRawAndPersistedFixIdentityValues() {
    final String rawClOrdId = "X".repeat(300);
    final String rawOrigClOrdId = "Y".repeat(300);
    final SubmissionResult rejectedSubmission =
        new SubmissionResult(
            normalize("cmd-3"),
            "CLIENT",
            "SIMPLEMATCH",
            LocalDate.of(2024, 3, 27),
            "O-C3",
            rawClOrdId,
            rawOrigClOrdId,
            CommandType.COMMAND_TYPE_CANCEL,
            false,
            "OVERSIZED_CL_ORD_ID",
            "cl_ord_id must be <= 64 characters",
            102L,
            "a".repeat(64),
            "b".repeat(64),
            true);

    repository.insert(rejectedSubmission, OUTBOX_EVENT_ID_ONE);

    assertThat(repository.findByBusinessKey(rejectedSubmission.businessKey()))
        .contains(rejectedSubmission);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT raw_cl_ord_id FROM risk_submissions WHERE request_id = ?",
                String.class,
                rejectedSubmission.requestId()))
        .isEqualTo(rawClOrdId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT cl_ord_id FROM risk_submissions WHERE request_id = ?",
                String.class,
                rejectedSubmission.requestId()))
        .isEqualTo(rejectedSubmission.persistedClOrdId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT business_key_surrogated FROM risk_submissions WHERE request_id = ?",
                Boolean.class,
                rejectedSubmission.requestId()))
        .isTrue();
  }

  @Test
  void allowsSamePersistedBusinessKeyWhenSurrogateFlagDiffers() {
    final String persistedKey = "a".repeat(64);
    final SubmissionResult plainSubmission =
        new SubmissionResult(
            normalize("cmd-plain"),
            "CLIENT",
            "SIMPLEMATCH",
            LocalDate.of(2024, 3, 27),
            "O-C-plain",
            persistedKey,
            "",
            CommandType.COMMAND_TYPE_NEW,
            true,
            "",
            "",
            103L);
    final SubmissionResult surrogatedSubmission =
        new SubmissionResult(
            normalize("cmd-surrogate"),
            "CLIENT",
            "SIMPLEMATCH",
            LocalDate.of(2024, 3, 27),
            "O-C-surrogate",
            "X".repeat(300),
            "",
            CommandType.COMMAND_TYPE_NEW,
            false,
            "OVERSIZED_CL_ORD_ID",
            "cl_ord_id must be <= 64 characters",
            104L,
            persistedKey,
            "",
            true);

    repository.insert(plainSubmission, OUTBOX_EVENT_ID_ONE);
    repository.insert(surrogatedSubmission, OUTBOX_EVENT_ID_TWO);

    assertThat(repository.findByBusinessKey(plainSubmission.businessKey()))
        .contains(plainSubmission);
    assertThat(repository.findByBusinessKey(surrogatedSubmission.businessKey()))
        .contains(surrogatedSubmission);
  }

  private SubmissionResult acceptedSubmission() {
    return new SubmissionResult(
        normalize("cmd-1"),
        "CLIENT",
        "SIMPLEMATCH",
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
