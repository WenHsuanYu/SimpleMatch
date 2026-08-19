package com.simplematch.tools.riskmatchinge2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.orders.v2.OrderAdmissionAccepted;
import com.simplematch.contracts.orders.v2.OrderAdmissionRejected;
import com.simplematch.contracts.risk.v2.AdmissionOutcomeStatus;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.contracts.risk.v2.OrderAdmissionResponse;
import com.simplematch.marketreference.ArtifactInstrument;
import com.simplematch.marketreference.InstrumentEligibility;
import com.simplematch.marketreference.InstrumentRef;
import com.simplematch.marketreference.MarketRule;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionPath;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.AdmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskAdmissionProbe.SubmissionObservation;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.CommandIdentity;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.MarketExpectation;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.RunIdentity;
import com.simplematch.tools.riskmatchinge2e.RiskMatchingScenario.Scenario;
import io.grpc.Status;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verifies the Risk admission state machine without sleeping or starting a gRPC server. */
class RiskAdmissionProbeTest {
  private static final UUID COMMAND_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ACCOUNT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final int PARTITION = 7;

  @Test
  void acceptsSynchronousAdmissionWithoutReconciliation() {
    final Fixture fixture = fixture(acceptedResponse());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final AdmissionObservation outcome =
        fixture.probe().awaitAccepted(fixture.scenario(), submission, fixture.deadline());

    assertEquals(AdmissionPath.SYNCHRONOUS_ACCEPTED, outcome.path());
    assertFalse(outcome.pendingObserved());
    assertEquals(0, outcome.reconciliationAttempts());
    assertEquals(0, fixture.gateway().lookupCalls());
  }

  @Test
  void reconcilesUnavailablePendingAdmissionToAccepted() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(pendingOutcome());
    fixture.gateway().enqueueLookup(acceptedOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final AdmissionObservation outcome =
        fixture.probe().awaitAccepted(fixture.scenario(), submission, fixture.deadline());

    assertEquals(AdmissionPath.RECOVERED_ACCEPTED, outcome.path());
    assertTrue(outcome.pendingObserved());
    assertEquals(2, outcome.reconciliationAttempts());
    assertEquals(Status.Code.UNAVAILABLE, outcome.initialGrpcCode());
  }

  @Test
  void acceptsAlreadyRecoveredAdmissionOnFirstReconciliationLookup() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(acceptedOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final AdmissionObservation outcome =
        fixture.probe().awaitAccepted(fixture.scenario(), submission, fixture.deadline());

    assertEquals(AdmissionPath.RECOVERED_ACCEPTED, outcome.path());
    assertFalse(outcome.pendingObserved());
    assertEquals(1, outcome.reconciliationAttempts());
  }

  @Test
  void failsImmediatelyWhenUnavailableAdmissionIsNotDurable() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(notFoundOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                fixture.probe().awaitAccepted(
                    fixture.scenario(), submission, fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_NOT_FOUND, failure.code());
    assertEquals(1, fixture.gateway().lookupCalls());
  }

  @Test
  void failsWhenRecoveredAdmissionTerminatesRejected() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(pendingOutcome());
    fixture.gateway().enqueueLookup(rejectedOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                fixture.probe().awaitAccepted(
                    fixture.scenario(), submission, fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_REJECTED, failure.code());
  }

  @Test
  void failsWhenAdmissionRemainsPendingUntilSharedDeadline() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException(), Duration.ofSeconds(1));
    fixture.gateway().repeatLookup(pendingOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                fixture.probe().awaitAccepted(
                    fixture.scenario(), submission, fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_REMAINED_PENDING, failure.code());
  }

  @Test
  void retriesTransientUnavailableReconciliationLookup() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(acceptedOutcome());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final AdmissionObservation outcome =
        fixture.probe().awaitAccepted(fixture.scenario(), submission, fixture.deadline());

    assertEquals(AdmissionPath.RECOVERED_ACCEPTED, outcome.path());
    assertEquals(2, outcome.reconciliationAttempts());
  }

  @Test
  void rejectsReconciliationIdentityMismatch() {
    final Fixture fixture = fixture(Status.UNAVAILABLE.asRuntimeException());
    fixture.gateway().enqueueLookup(
        acceptedOutcome().toBuilder().setOrderId(UUID.randomUUID().toString()).build());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                fixture.probe().awaitAccepted(
                    fixture.scenario(), submission, fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_IDENTITY_MISMATCH, failure.code());
  }

  @Test
  void rejectsSynchronousBusinessRejection() {
    final Fixture fixture = fixture(rejectedResponse());

    final SubmissionObservation submission =
        fixture.probe().submit(fixture.scenario(), fixture.deadline());
    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () ->
                fixture.probe().awaitAccepted(
                    fixture.scenario(), submission, fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_REJECTED, failure.code());
    assertEquals(0, fixture.gateway().lookupCalls());
  }

  @Test
  void rejectsNonRecoverableSubmissionGrpcFailure() {
    final Fixture fixture = fixture(Status.INVALID_ARGUMENT.asRuntimeException());

    final VerificationFailure failure =
        assertThrows(
            VerificationFailure.class,
            () -> fixture.probe().submit(fixture.scenario(), fixture.deadline()));

    assertEquals(VerificationFailure.Code.ADMISSION_SUBMISSION_FAILED, failure.code());
  }

  private static Fixture fixture(Object submission) {
    return fixture(submission, Duration.ofSeconds(90));
  }

  private static Fixture fixture(Object submission, Duration timeout) {
    final AtomicLong ticker = new AtomicLong();
    final FakeGateway gateway = new FakeGateway(submission);
    final VerificationDeadline deadline = new VerificationDeadline(timeout, ticker::get);
    final RiskAdmissionProbe probe =
        new RiskAdmissionProbe(
            gateway,
            duration -> ticker.addAndGet(duration.toNanos()),
            ticker::get);
    return new Fixture(probe, gateway, deadline, scenario());
  }

  private static Scenario scenario() {
    final RunIdentity run =
        new RunIdentity("test-run", LocalDate.of(2026, 8, 17), ACCOUNT_ID);
    final ArtifactInstrument instrument =
        new ArtifactInstrument(
            new InstrumentRef("XTAI", "2330"),
            InstrumentEligibility.ELIGIBLE,
            null,
            "TWD-EQUITY",
            10_000L,
            9_000L,
            11_000L);
    final MarketRule rule = new MarketRule("TWD-EQUITY", 1_000, "TWSE-TICK");
    final MarketExpectation market =
        new MarketExpectation("a".repeat(64), "stable-least-loaded-v1", PARTITION, instrument, rule);
    final CommandIdentity command = new CommandIdentity(COMMAND_ID, ORDER_ID);
    final NewOrderCommand request =
        NewOrderCommand.newBuilder()
            .setCommandId(COMMAND_ID.toString())
            .setOrderId(ORDER_ID.toString())
            .setAccountId(ACCOUNT_ID.toString())
            .setSide(Side.SIDE_BUY)
            .setOrderType(OrderType.ORDER_TYPE_LIMIT)
            .setTif(TimeInForce.TIME_IN_FORCE_ROD)
            .build();
    return new Scenario(run, market, command, request);
  }

  private static OrderAdmissionResponse acceptedResponse() {
    return OrderAdmissionResponse.newBuilder()
        .setAccepted(
            OrderAdmissionAccepted.newBuilder()
                .setCommandId(COMMAND_ID.toString())
                .setOrderId(ORDER_ID.toString())
                .setAccountId(ACCOUNT_ID.toString())
                .setRoutingPartition(PARTITION))
        .build();
  }

  private static OrderAdmissionResponse rejectedResponse() {
    return OrderAdmissionResponse.newBuilder()
        .setRejected(
            OrderAdmissionRejected.newBuilder()
                .setCommandId(COMMAND_ID.toString())
                .setOrderId(ORDER_ID.toString())
                .setAccountId(ACCOUNT_ID.toString())
                .setReasonCode("TEST_REJECTION")
                .setReasonDetail("synthetic rejection"))
        .build();
  }

  private static GetAdmissionOutcomeResponse pendingOutcome() {
    return outcome(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_PENDING);
  }

  private static GetAdmissionOutcomeResponse acceptedOutcome() {
    return outcome(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_ACCEPTED);
  }

  private static GetAdmissionOutcomeResponse rejectedOutcome() {
    return outcome(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_REJECTED).toBuilder()
        .setReasonCode("RECOVERY_REJECTED")
        .setReasonDetail("synthetic recovery rejection")
        .build();
  }

  private static GetAdmissionOutcomeResponse notFoundOutcome() {
    return GetAdmissionOutcomeResponse.newBuilder()
        .setCommandId(COMMAND_ID.toString())
        .setStatus(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_NOT_FOUND)
        .build();
  }

  private static GetAdmissionOutcomeResponse outcome(AdmissionOutcomeStatus status) {
    return GetAdmissionOutcomeResponse.newBuilder()
        .setCommandId(COMMAND_ID.toString())
        .setStatus(status)
        .setOrderId(ORDER_ID.toString())
        .setAccountId(ACCOUNT_ID.toString())
        .build();
  }

  private record Fixture(
      RiskAdmissionProbe probe,
      FakeGateway gateway,
      VerificationDeadline deadline,
      Scenario scenario) {}

  private static final class FakeGateway implements RiskAdmissionProbe.RiskGateway {
    private final Object submission;
    private final Deque<Object> lookups = new ArrayDeque<>();
    private Object repeatedLookup;
    private int lookupCalls;

    private FakeGateway(Object submission) {
      this.submission = submission;
    }

    private void enqueueLookup(Object lookup) {
      lookups.addLast(lookup);
    }

    private void repeatLookup(Object lookup) {
      repeatedLookup = lookup;
    }

    private int lookupCalls() {
      return lookupCalls;
    }

    @Override
    public OrderAdmissionResponse submit(NewOrderCommand request, Duration timeout) {
      return valueOrThrow(submission, OrderAdmissionResponse.class);
    }

    @Override
    public GetAdmissionOutcomeResponse lookup(String commandId, Duration timeout) {
      lookupCalls++;
      final Object value = lookups.isEmpty() ? repeatedLookup : lookups.removeFirst();
      if (value == null) {
        throw new AssertionError("unexpected reconciliation lookup");
      }
      return valueOrThrow(value, GetAdmissionOutcomeResponse.class);
    }

    private static <T> T valueOrThrow(Object value, Class<T> type) {
      if (value instanceof RuntimeException failure) {
        throw failure;
      }
      return type.cast(value);
    }
  }
}
