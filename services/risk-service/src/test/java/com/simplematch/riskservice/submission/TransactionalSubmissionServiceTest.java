package com.simplematch.riskservice.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.simplematch.riskservice.submission.SubmissionCommandFixtures.resolvedNewOrder;
import static com.simplematch.riskservice.testsupport.TestCommandIds.normalize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import com.simplematch.riskservice.outbox.SubmissionOutboxFactory;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionalSubmissionServiceTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC);

  @Test
  void persistsSubmissionAndOutboxWhenBusinessKeyIsNew() {
    final RecordingSubmissionRepository submissionRepository = new RecordingSubmissionRepository();
    submissionRepository.queueFindResult(Optional.empty());
    final RecordingOutboxRepository outboxRepository = new RecordingOutboxRepository();
    final TransactionalSubmissionService service = new TransactionalSubmissionService(
        new SubmissionValidator(FIXED_CLOCK),
        new SubmissionOutboxFactory(new ObjectMapper(), "orders.validated"),
        submissionRepository,
        outboxRepository,
        newTransactionTemplate());

    final SubmissionResult submission = service.persist(resolvedNewOrder("cmd-1", "O-C1", "C1"));

    assertThat(submission.accepted()).isTrue();
    assertThat(submissionRepository.insertedSubmission).isEqualTo(submission);
    assertThat(submissionRepository.insertedOutboxEventId).isEqualTo(outboxRepository.inserted.getFirst().eventId());
    assertThat(outboxRepository.inserted).hasSize(1);
    assertThat(outboxRepository.inserted.getFirst().aggregateId()).isEqualTo("O-C1");
    assertThat(outboxRepository.inserted.getFirst().topic()).isEqualTo("orders.validated");
  }

  @Test
  void returnsExistingSubmissionWithoutBuildingOutbox() {
    final RecordingSubmissionRepository submissionRepository = new RecordingSubmissionRepository();
    final SubmissionResult existing = new SubmissionResult(
        normalize("cmd-existing"),
      "CLIENT",
      "SIMPLEMATCH",
        LocalDate.of(2024, 3, 27),
        "O-C1",
        "C1",
        "",
        CommandType.COMMAND_TYPE_NEW,
        true,
        "",
        "",
        99L);
    submissionRepository.queueFindResult(Optional.of(existing));
    final TransactionalSubmissionService service = new TransactionalSubmissionService(
        new SubmissionValidator(FIXED_CLOCK),
        new SubmissionOutboxFactory(failingObjectMapper(), "orders.validated"),
        submissionRepository,
        new RecordingOutboxRepository(),
        newTransactionTemplate());

    final SubmissionResult submission = service.persist(resolvedNewOrder("cmd-1", "O-C1", "C1"));

    assertThat(submission).isEqualTo(existing);
    assertThat(submissionRepository.insertedSubmission).isNull();
  }

  @Test
  void returnsExistingSubmissionWhenInsertHitsDuplicateKey() {
    final RecordingSubmissionRepository submissionRepository = new RecordingSubmissionRepository();
    final SubmissionResult existing = new SubmissionResult(
        normalize("cmd-winner"),
      "CLIENT",
      "SIMPLEMATCH",
        LocalDate.of(2024, 3, 27),
        "O-C1",
        "C1",
        "",
        CommandType.COMMAND_TYPE_NEW,
        true,
        "",
        "",
        100L);
    submissionRepository.queueFindResult(Optional.empty());
    submissionRepository.queueFindResult(Optional.of(existing));
    submissionRepository.failWithDuplicateKey = true;
    final RecordingOutboxRepository outboxRepository = new RecordingOutboxRepository();
    final TransactionalSubmissionService service = new TransactionalSubmissionService(
        new SubmissionValidator(FIXED_CLOCK),
        new SubmissionOutboxFactory(new ObjectMapper(), "orders.validated"),
        submissionRepository,
        outboxRepository,
        newTransactionTemplate());

    final SubmissionResult submission = service.persist(resolvedNewOrder("cmd-loser", "O-C1", "C1"));

    assertThat(submission).isEqualTo(existing);
    assertThat(outboxRepository.inserted).isEmpty();
  }

  @Test
  void rethrowsDuplicateKeyWhenConflictCannotBeResolved() {
    final RecordingSubmissionRepository submissionRepository = new RecordingSubmissionRepository();
    submissionRepository.queueFindResult(Optional.empty());
    submissionRepository.queueFindResult(Optional.empty());
    submissionRepository.failWithDuplicateKey = true;
    final TransactionalSubmissionService service = new TransactionalSubmissionService(
        new SubmissionValidator(FIXED_CLOCK),
        new SubmissionOutboxFactory(new ObjectMapper(), "orders.validated"),
        submissionRepository,
        new RecordingOutboxRepository(),
        newTransactionTemplate());

    assertThatThrownBy(() -> service.persist(resolvedNewOrder("cmd-1", "O-C1", "C1")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private static TransactionTemplate newTransactionTemplate() {
    return new TransactionTemplate(new NoOpTransactionManager());
  }

  private static ObjectMapper failingObjectMapper() {
    return new ObjectMapper() {
      @Override
      public String writeValueAsString(Object value) throws JsonProcessingException {
        throw new JsonProcessingException("boom") {
          private static final long serialVersionUID = 1L;
        };
      }
    };
  }

  private static final class RecordingSubmissionRepository implements SubmissionRepository {
    private final ArrayDeque<Optional<SubmissionResult>> findResults = new ArrayDeque<>();
    private SubmissionResult insertedSubmission;
    private String insertedOutboxEventId;
    private boolean failWithDuplicateKey;

    private void queueFindResult(Optional<SubmissionResult> result) {
      findResults.addLast(result);
    }

    @Override
    public Optional<SubmissionResult> findByBusinessKey(SubmissionBusinessKey businessKey) {
      return findResults.isEmpty() ? Optional.empty() : findResults.removeFirst();
    }

    @Override
    public void insert(SubmissionResult submission, String outboxEventId) {
      if (failWithDuplicateKey) {
        throw new DuplicateKeyException("duplicate");
      }
      insertedSubmission = submission;
      insertedOutboxEventId = outboxEventId;
    }
  }

  private static final class RecordingOutboxRepository implements OutboxRepository {
    private final List<OutboxRecord> inserted = new ArrayList<>();

    @Override
    public void insert(OutboxRecord record) {
      inserted.add(record);
    }
  }

  private static final class NoOpTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}