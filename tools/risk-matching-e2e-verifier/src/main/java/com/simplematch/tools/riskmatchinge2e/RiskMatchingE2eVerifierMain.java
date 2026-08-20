package com.simplematch.tools.riskmatchinge2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.SubmissionObservation;
import com.simplematch.tools.riskmatchinge2e.VerifierArguments.VerificationMode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Deployed RM-1 verifier entry point.
 *
 * <p>INITIAL mode submits one real Risk order and proves the resulting MatchingCommand on the
 * production {@code matching.commands} topic. REPLAY mode resubmits the same durably-equivalent
 * command identity after an externally controlled restart boundary and requires Risk to return the
 * already-terminal admission synchronously. PostgreSQL/outbox inspection remains in the outer shell
 * harness.
 */
public final class RiskMatchingE2eVerifierMain {
  private RiskMatchingE2eVerifierMain() {}

  /** Runs one bounded deployed verification and writes machine-readable evidence. */
  public static void main(String[] args) throws Exception {
    final VerifierArguments arguments = VerifierArguments.parse(args);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final var evidenceDir = arguments.execution().evidenceDir();
    VerificationEvidenceDirectory.prepare(evidenceDir);
    final VerificationEvidenceWriter evidence = new VerificationEvidenceWriter(json, evidenceDir);

    try {
      run(arguments, json, evidence);
    } catch (Exception failure) {
      if (!Files.exists(evidenceDir.resolve("verifier-verdict.json"))) {
        evidence.writeFailure(failure, null);
      }
      throw failure;
    }
  }

  private static void run(
      VerifierArguments arguments,
      ObjectMapper json,
      VerificationEvidenceWriter evidence) throws Exception {
    final VerificationDeadline deadline =
        VerificationDeadline.start(arguments.execution().timeout());
    final byte[] artifactBytes = Files.readAllBytes(arguments.artifact().artifactPath());
    final String checksum = Files.readString(arguments.artifact().checksumPath()).trim();
    final VerifiedMarketReferenceArtifact verified =
        new MarketReferenceArtifactStartupValidator(json)
            .validate(artifactBytes, checksum, arguments.run().tradingDay());
    final RiskMatchingScenario.Scenario scenario =
        RiskMatchingScenario.create(verified, arguments.run(), Instant.now());

    evidence.writeSelectedInstrument(scenario);
    evidence.writeRequest(scenario.request());

    try {
      if (arguments.execution().mode() == VerificationMode.REPLAY) {
        final ReplayEvidenceWriter replayEvidence =
            new ReplayEvidenceWriter(json, arguments.execution().evidenceDir());
        verifyReplayScenario(arguments, scenario, deadline, evidence, replayEvidence);
      } else {
        verifyInitialScenario(arguments, scenario, deadline, evidence);
      }
    } catch (Exception failure) {
      evidence.writeFailure(failure, scenario.command().commandId().toString());
      throw failure;
    }
  }

  private static void verifyInitialScenario(
      VerifierArguments arguments,
      RiskMatchingScenario.Scenario scenario,
      VerificationDeadline deadline,
      VerificationEvidenceWriter evidence) throws Exception {
    final VerifierArguments.ServiceEndpoints services = arguments.services();
    final ManagedChannel channel = createRiskChannel(services.riskTarget());

    try (KafkaMatchingCommandProbe kafka =
        new KafkaMatchingCommandProbe(
            services.kafkaBootstrap(), services.topic(), arguments.run().runId())) {
      final Map<Integer, Long> offsetsBefore = snapshotOffsets(kafka);
      evidence.writeOffsets(services.topic(), offsetsBefore);

      final RiskAdmissionProbe admissionProbe = new RiskAdmissionProbe(channel);
      final SubmissionObservation submission = admissionProbe.submit(scenario, deadline);
      evidence.writeSubmission(submission);
      final AdmissionObservation admission =
          admissionProbe.awaitAccepted(scenario, submission, deadline);
      evidence.writeAdmissionOutcome(admission);

      final Duration kafkaBudget =
          deadline.requireRemaining(
              VerificationFailure.Stage.KAFKA_OBSERVATION,
              VerificationFailure.Code.KAFKA_COMMAND_NOT_OBSERVED,
              "verifier deadline expired before matching command observation");
      final KafkaMatchingCommandProbe.ProbeResult observed =
          awaitCommand(kafka, scenario, offsetsBefore, kafkaBudget);
      validateMatchingCommand(scenario, observed);

      evidence.writeRecord(observed);
      evidence.writeMatchingCommand(observed.command());
      evidence.writePass(admission, observed);
    } finally {
      closeRiskChannel(channel);
    }
  }

  private static void verifyReplayScenario(
      VerifierArguments arguments,
      RiskMatchingScenario.Scenario scenario,
      VerificationDeadline deadline,
      VerificationEvidenceWriter evidence,
      ReplayEvidenceWriter replayEvidence) throws Exception {
    final ManagedChannel channel = createRiskChannel(arguments.services().riskTarget());
    try {
      final RiskAdmissionProbe admissionProbe = new RiskAdmissionProbe(channel);
      final SubmissionObservation submission = admissionProbe.submit(scenario, deadline);
      evidence.writeSubmission(submission);
      final AdmissionObservation admission =
          admissionProbe.awaitAccepted(scenario, submission, deadline);
      evidence.writeAdmissionOutcome(admission);
      requireTerminalReplay(submission, admission);
      replayEvidence.writePass(admission);
    } finally {
      closeRiskChannel(channel);
    }
  }

  private static void requireTerminalReplay(
      SubmissionObservation submission, AdmissionObservation admission) {
    if (submission.grpcCode() != Status.Code.OK
        || admission.path() != RiskAdmissionProbe.AdmissionPath.SYNCHRONOUS_ACCEPTED) {
      throw new VerificationFailure(
          VerificationFailure.Stage.ADMISSION_REPLAY,
          VerificationFailure.Code.ADMISSION_REPLAY_NOT_TERMINAL,
          "equivalent replay did not return the durable terminal admission synchronously");
    }
  }

  private static ManagedChannel createRiskChannel(String target) {
    return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
  }

  private static void closeRiskChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);
  }

  private static Map<Integer, Long> snapshotOffsets(KafkaMatchingCommandProbe kafka) {
    try {
      return kafka.snapshotEndOffsets();
    } catch (RuntimeException failure) {
      throw new VerificationFailure(
          VerificationFailure.Stage.KAFKA_OBSERVATION,
          VerificationFailure.Code.KAFKA_OBSERVATION_FAILED,
          "could not snapshot matching.commands offsets before Risk submission",
          failure);
    }
  }

  private static KafkaMatchingCommandProbe.ProbeResult awaitCommand(
      KafkaMatchingCommandProbe kafka,
      RiskMatchingScenario.Scenario scenario,
      Map<Integer, Long> offsetsBefore,
      Duration timeout) {
    try {
      return kafka.awaitCommand(
          scenario.command().commandId().toString(),
          scenario.market().expectedPartition(),
          offsetsBefore,
          timeout);
    } catch (RuntimeException failure) {
      final String message =
          failure.getMessage() == null
              ? "matching command observation failed"
              : failure.getMessage();
      throw new VerificationFailure(
          VerificationFailure.Stage.KAFKA_OBSERVATION,
          VerificationFailure.Code.KAFKA_COMMAND_NOT_OBSERVED,
          message,
          failure);
    }
  }

  private static void validateMatchingCommand(
      RiskMatchingScenario.Scenario scenario,
      KafkaMatchingCommandProbe.ProbeResult observed) {
    try {
      RiskMatchingScenario.validateMatchingCommand(scenario, observed.command());
    } catch (RuntimeException failure) {
      final String message =
          failure.getMessage() == null
              ? "matching command validation failed"
              : failure.getMessage();
      throw new VerificationFailure(
          VerificationFailure.Stage.KAFKA_VALIDATION,
          VerificationFailure.Code.KAFKA_COMMAND_INVALID,
          message,
          failure);
    }
  }
}
