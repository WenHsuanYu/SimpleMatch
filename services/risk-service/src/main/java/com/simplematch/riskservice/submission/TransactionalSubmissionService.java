package com.simplematch.riskservice.submission;

import com.simplematch.riskservice.outbox.OutboxEventFactory;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists submission state and outbox records within one local transaction. */
public final class TransactionalSubmissionService implements SubmissionService {
  private final SubmissionValidator submissionValidator;
  private final OutboxEventFactory<SubmissionDecision> outboxEventFactory;
  private final SubmissionRepository submissionRepository;
  private final OutboxRepository outboxRepository;
  private final TransactionTemplate transactionTemplate;

  /**
   * Creates the transactional submission service with its validation and persistence dependencies.
   */
  public TransactionalSubmissionService(
      SubmissionValidator submissionValidator,
      OutboxEventFactory<SubmissionDecision> outboxEventFactory,
      SubmissionRepository submissionRepository,
      OutboxRepository outboxRepository,
      TransactionTemplate transactionTemplate) {
    this.submissionValidator = Objects.requireNonNull(submissionValidator);
    this.outboxEventFactory = Objects.requireNonNull(outboxEventFactory);
    this.submissionRepository = Objects.requireNonNull(submissionRepository);
    this.outboxRepository = Objects.requireNonNull(outboxRepository);
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
  }

  @Override
  public SubmissionResult persist(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand =
        command == null ? ResolvedSubmissionCommand.unspecified() : command;
    final SubmissionDecision decision = submissionValidator.evaluate(normalizedCommand);

    final SubmissionResult persistedSubmission =
        transactionTemplate.execute(status -> persist(decision));
    if (persistedSubmission == null) {
      throw new IllegalStateException("risk submission transaction returned null");
    }
    return persistedSubmission;
  }

  private SubmissionResult persist(SubmissionDecision decision) {
    final SubmissionBusinessKey businessKey = decision.submission().businessKey();
    final SubmissionResult existing =
        submissionRepository.findByBusinessKey(businessKey).orElse(null);
    if (existing != null) {
      return existing;
    }

    final OutboxRecord outboxRecord = outboxEventFactory.create(decision);

    try {
      submissionRepository.insert(decision.submission(), outboxRecord.eventId());
      outboxRepository.insert(outboxRecord);
      return decision.submission();
    } catch (DuplicateKeyException duplicateKeyException) {
      return submissionRepository
          .findByBusinessKey(businessKey)
          .orElseThrow(() -> duplicateKeyException);
    }
  }
}
