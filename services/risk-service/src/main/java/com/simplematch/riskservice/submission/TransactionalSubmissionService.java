package com.simplematch.riskservice.submission;

import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalSubmissionService implements SubmissionService {
  private final SubmissionValidator submissionValidator;
  private final SubmissionOutboxFactory submissionOutboxFactory;
  private final SubmissionRepository submissionRepository;
  private final OutboxRepository outboxRepository;
  private final TransactionTemplate transactionTemplate;

  public TransactionalSubmissionService(
      SubmissionValidator submissionValidator,
      SubmissionOutboxFactory submissionOutboxFactory,
      SubmissionRepository submissionRepository,
      OutboxRepository outboxRepository,
      TransactionTemplate transactionTemplate) {
    this.submissionValidator = Objects.requireNonNull(submissionValidator);
    this.submissionOutboxFactory = Objects.requireNonNull(submissionOutboxFactory);
    this.submissionRepository = Objects.requireNonNull(submissionRepository);
    this.outboxRepository = Objects.requireNonNull(outboxRepository);
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
  }

  @Override
  public SubmissionResult persist(ResolvedSubmissionCommand command) {
    final ResolvedSubmissionCommand normalizedCommand = command == null
        ? ResolvedSubmissionCommand.unspecified()
        : command;
    final SubmissionDecision decision = submissionValidator.evaluate(normalizedCommand);

    final SubmissionResult persistedSubmission = transactionTemplate.execute(status -> persist(decision));
    if (persistedSubmission == null) {
      throw new IllegalStateException("risk submission transaction returned null");
    }
    return persistedSubmission;
  }

  private SubmissionResult persist(SubmissionDecision decision) {
    final SubmissionBusinessKey businessKey = decision.submission().businessKey();
    final SubmissionResult existing = submissionRepository.findByBusinessKey(businessKey).orElse(null);
    if (existing != null) {
      return existing;
    }

    final OutboxRecord outboxRecord = submissionOutboxFactory.create(decision);

    try {
      submissionRepository.insert(decision.submission(), outboxRecord.eventId());
      outboxRepository.insert(outboxRecord);
      return decision.submission();
    } catch (DuplicateKeyException duplicateKeyException) {
      return submissionRepository.findByBusinessKey(businessKey)
          .orElseThrow(() -> duplicateKeyException);
    }
  }
}