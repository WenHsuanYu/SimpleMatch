package com.simplematch.riskservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.risk.v2.AdmissionOutcomeStatus;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeRequest;
import com.simplematch.contracts.risk.v2.GetAdmissionOutcomeResponse;
import com.simplematch.riskservice.admission.AdmissionResult;
import com.simplematch.riskservice.admission.AdmissionState;
import com.simplematch.riskservice.admission.OrderAdmissionApplicationService;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderAdmissionGrpcServiceReconciliationTest {
  private static final UUID COMMAND_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11");
  private static final UUID ORDER_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c12");
  private static final UUID ACCOUNT_ID =
      UUID.fromString("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void reconciliationReturnsPendingForDurableInFlightAdmission() {
    final OrderAdmissionApplicationService admissions = mock(OrderAdmissionApplicationService.class);
    final AdmissionResult pending = result(AdmissionState.PENDING, "", "");
    when(admissions.findOutcome(COMMAND_ID)).thenReturn(Optional.of(pending));
    final OrderAdmissionGrpcService service = new OrderAdmissionGrpcService(admissions, CLOCK);
    final TestStreamObserver<GetAdmissionOutcomeResponse> observer = new TestStreamObserver<>();

    service.getAdmissionOutcome(request(COMMAND_ID), observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getStatus())
        .isEqualTo(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_PENDING);
    assertThat(observer.value().getCommandId()).isEqualTo(COMMAND_ID.toString());
    assertThat(observer.value().getOrderId()).isEqualTo(ORDER_ID.toString());
  }

  @Test
  void reconciliationReturnsAcceptedForTerminalAdmission() {
    final OrderAdmissionApplicationService admissions = mock(OrderAdmissionApplicationService.class);
    final AdmissionResult accepted = result(AdmissionState.ACCEPTED, "", "");
    when(admissions.findOutcome(COMMAND_ID)).thenReturn(Optional.of(accepted));
    final OrderAdmissionGrpcService service = new OrderAdmissionGrpcService(admissions, CLOCK);
    final TestStreamObserver<GetAdmissionOutcomeResponse> observer = new TestStreamObserver<>();

    service.getAdmissionOutcome(request(COMMAND_ID), observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getStatus())
        .isEqualTo(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_ACCEPTED);
  }

  @Test
  void reconciliationReturnsRejectedWithAuthoritativeReason() {
    final OrderAdmissionApplicationService admissions = mock(OrderAdmissionApplicationService.class);
    final AdmissionResult rejected =
        result(AdmissionState.REJECTED, "ACCOUNT_LIMIT", "available limit is insufficient");
    when(admissions.findOutcome(COMMAND_ID)).thenReturn(Optional.of(rejected));
    final OrderAdmissionGrpcService service = new OrderAdmissionGrpcService(admissions, CLOCK);
    final TestStreamObserver<GetAdmissionOutcomeResponse> observer = new TestStreamObserver<>();

    service.getAdmissionOutcome(request(COMMAND_ID), observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getStatus())
        .isEqualTo(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_REJECTED);
    assertThat(observer.value().getReasonCode()).isEqualTo("ACCOUNT_LIMIT");
    assertThat(observer.value().getReasonDetail()).isEqualTo("available limit is insufficient");
  }

  @Test
  void reconciliationReturnsNotFoundAsANormalOutcome() {
    final OrderAdmissionApplicationService admissions = mock(OrderAdmissionApplicationService.class);
    when(admissions.findOutcome(COMMAND_ID)).thenReturn(Optional.empty());
    final OrderAdmissionGrpcService service = new OrderAdmissionGrpcService(admissions, CLOCK);
    final TestStreamObserver<GetAdmissionOutcomeResponse> observer = new TestStreamObserver<>();

    service.getAdmissionOutcome(request(COMMAND_ID), observer);

    assertThat(observer.completed()).isTrue();
    assertThat(observer.error()).isNull();
    assertThat(observer.value().getStatus())
        .isEqualTo(AdmissionOutcomeStatus.ADMISSION_OUTCOME_STATUS_NOT_FOUND);
    assertThat(observer.value().getCommandId()).isEqualTo(COMMAND_ID.toString());
  }

  @Test
  void reconciliationRejectsInvalidCommandIdentity() {
    final OrderAdmissionApplicationService admissions = mock(OrderAdmissionApplicationService.class);
    final OrderAdmissionGrpcService service = new OrderAdmissionGrpcService(admissions, CLOCK);
    final TestStreamObserver<GetAdmissionOutcomeResponse> observer = new TestStreamObserver<>();

    service.getAdmissionOutcome(
        GetAdmissionOutcomeRequest.newBuilder().setCommandId("not-a-uuid").build(), observer);

    assertThat(observer.completed()).isFalse();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(admissions);
  }

  private GetAdmissionOutcomeRequest request(UUID commandId) {
    return GetAdmissionOutcomeRequest.newBuilder().setCommandId(commandId.toString()).build();
  }

  private AdmissionResult result(AdmissionState state, String reasonCode, String reasonDetail) {
    final AdmissionResult result = mock(AdmissionResult.class);
    when(result.commandId()).thenReturn(COMMAND_ID);
    when(result.orderId()).thenReturn(ORDER_ID);
    when(result.accountId()).thenReturn(ACCOUNT_ID);
    when(result.state()).thenReturn(state);
    when(result.reasonCode()).thenReturn(reasonCode);
    when(result.reasonDetail()).thenReturn(reasonDetail);
    return result;
  }
}
