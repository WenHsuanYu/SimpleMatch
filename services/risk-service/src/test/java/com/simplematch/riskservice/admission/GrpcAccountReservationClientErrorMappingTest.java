package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.config.GrpcProperties;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.account.v2.AccountReservationServiceGrpc;
import com.simplematch.contracts.account.v2.ReservationCommand;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcAccountReservationClientErrorMappingTest {
  private static final UUID COMMAND_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11");
  private static final UUID ORDER_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c12");
  private static final UUID ACCOUNT_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13");

  private Server server;

  @AfterEach
  void stopServer() throws InterruptedException {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @DisplayName("Account business rejection remains a rejected reservation outcome")
  @Test
  void mapsAccountBusinessRejectionWithoutCreatingTransportFailure() throws Exception {
    server = serverReturning(rejectedEvent());

    try (GrpcAccountReservationClient client = reservationClient()) {
      final ReservationOutcome outcome = client.reserve(command());

      assertThat(outcome)
          .isEqualTo(
              ReservationOutcome.rejected("LIMIT_EXCEEDED", "daily notional limit exceeded"));
    }
  }

  @DisplayName("Account validation remains a validation failure")
  @Test
  void mapsInvalidArgumentToValidation() throws Exception {
    server = serverFailing(Status.INVALID_ARGUMENT.withDescription("venue_mic is required"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOfSatisfying(
              AdmissionValidationException.class,
              failure -> {
                assertThat(failure.reasonCode()).isEqualTo("INVALID_COMMAND");
                assertThat(failure.detail()).contains("venue_mic is required");
              });
    }
  }

  @DisplayName("Account request conflicts remain stable conflicts")
  @Test
  void mapsAlreadyExistsToConflict() throws Exception {
    server = serverFailing(Status.ALREADY_EXISTS.withDescription("request conflict"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOf(AdmissionConflictException.class);
    }
  }

  @DisplayName("retryable Account transport failures remain recoverable")
  @Test
  void mapsUnavailableToRetryableFailure() throws Exception {
    server = serverFailing(Status.UNAVAILABLE.withDescription("account is restarting"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOf(AdmissionUnavailableException.class);
    }
  }

  @DisplayName("Account deadline failures remain recoverable")
  @Test
  void mapsDeadlineExceededToRetryableFailure() throws Exception {
    server = serverFailing(Status.DEADLINE_EXCEEDED.withDescription("account deadline"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOf(AdmissionUnavailableException.class);
    }
  }

  @DisplayName("unexpected Account failures remain distinct from transient unavailability")
  @Test
  void mapsInternalFailureToAccountFailure() throws Exception {
    server = serverFailing(Status.INTERNAL.withDescription("account invariant failure"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOf(AdmissionAccountFailureException.class)
          .isNotInstanceOf(AdmissionUnavailableException.class);
    }
  }

  @DisplayName("Account invariant failures remain distinct from validation and outage")
  @Test
  void mapsFailedPreconditionToInvariantFailure() throws Exception {
    server = serverFailing(Status.FAILED_PRECONDITION.withDescription("account invariant failure"));

    try (GrpcAccountReservationClient client = reservationClient()) {
      assertThatThrownBy(() -> client.reserve(command()))
          .isInstanceOf(AdmissionInvariantException.class)
          .isNotInstanceOf(AdmissionValidationException.class)
          .isNotInstanceOf(AdmissionUnavailableException.class)
          .isNotInstanceOf(AdmissionAccountFailureException.class);
    }
  }

  private Server serverReturning(AccountLifecycleEvent response) throws Exception {
    return ServerBuilder.forPort(0)
        .addService(
            new AccountReservationServiceGrpc.AccountReservationServiceImplBase() {
              @Override
              public void reserve(
                  ReservationCommand request,
                  StreamObserver<AccountLifecycleEvent> responseObserver) {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
              }
            })
        .build()
        .start();
  }

  private Server serverFailing(Status status) throws Exception {
    return ServerBuilder.forPort(0)
        .addService(
            new AccountReservationServiceGrpc.AccountReservationServiceImplBase() {
              @Override
              public void reserve(
                  ReservationCommand request,
                  StreamObserver<AccountLifecycleEvent> responseObserver) {
                responseObserver.onError(status.asRuntimeException());
              }
            })
        .build()
        .start();
  }

  private GrpcAccountReservationClient reservationClient() {
    return new GrpcAccountReservationClient(
        new GrpcProperties(
            new GrpcProperties.GrpcTargetsProperties(
                "localhost:" + server.getPort(), "localhost:0")));
  }

  private AccountLifecycleEvent rejectedEvent() {
    return AccountLifecycleEvent.newBuilder()
        .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_REJECTED)
        .setReasonCode("LIMIT_EXCEEDED")
        .setReasonDetail("daily notional limit exceeded")
        .build();
  }

  private AdmissionCommand command() {
    return new AdmissionCommand(
        new AdmissionIdentity(
            new AdmissionIdentity.CommandId(COMMAND_ID),
            new AdmissionIdentity.OrderId(ORDER_ID),
            new AdmissionIdentity.AccountId(ACCOUNT_ID)),
        new AdmissionOrder(
            new AdmissionOrder.Instrument(
                new AdmissionOrder.Symbol("2330"), new AdmissionOrder.VenueMic("XTAI")),
            new AdmissionOrder.Characteristics(
                new AdmissionOrder.SideCode("SIDE_BUY"),
                new AdmissionOrder.Quantity(10),
                new AdmissionOrder.LimitPriceUnits(1_000_000L),
                new AdmissionOrder.OrderTypeCode("ORDER_TYPE_LIMIT"),
                new AdmissionOrder.TimeInForceCode("TIME_IN_FORCE_ROD")),
            LocalDate.of(2026, 7, 28)),
        new AdmissionFixIdentity(
            new AdmissionFixIdentity.SenderCompId("CLIENT1"),
            new AdmissionFixIdentity.TargetCompId("SIMPLEMATCH"),
            new AdmissionFixIdentity.ClOrdId("C1")),
        new AdmissionRoutingReference(
            new AdmissionRoutingReference.RoutingSnapshotId(
                UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c15"))));
  }

}
