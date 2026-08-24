package com.simplematch.accountservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simplematch.accountservice.authority.AccountAuthorityReader;
import com.simplematch.accountservice.authority.AccountReservation;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Protects the retained matching execution compatibility path from final-event semantics. */
class AccountMatchingExecutionApplicationServiceTest {
  private static final String ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";

  @ParameterizedTest
  @EnumSource(
      value = ExecutionType.class,
      names = {"EXECUTION_TYPE_PARTIAL_FILL", "EXECUTION_TYPE_FILL"})
  void legacyFillsKeepReservationOwnedLifecycleSemantics(ExecutionType executionType) {
    final AccountAuthorityReader authorityReader = mock(AccountAuthorityReader.class);
    final AccountReservationApplicationService reservationService =
        mock(AccountReservationApplicationService.class);
    final AccountReservation reservation = mock(AccountReservation.class);
    final ReservationRecord applied = mock(ReservationRecord.class);
    when(authorityReader.findReservationByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
    when(reservation.accountId()).thenReturn(ACCOUNT_ID);
    when(reservation.symbol()).thenReturn("2330");
    when(reservation.requestId()).thenReturn("request-1");
    when(reservation.reservationId()).thenReturn("reservation-1");
    when(reservation.orderId()).thenReturn(ORDER_ID);
    when(reservationService.applyFill(any(ApplyFillOperation.class))).thenReturn(applied);
    final AccountMatchingExecutionApplicationService service =
        new AccountMatchingExecutionApplicationService(authorityReader, reservationService);
    final ExecutionEvent event =
        ExecutionEvent.newBuilder()
            .setExecId("legacy-execution-1")
            .setOrderId(ORDER_ID)
            .setAccountId(ACCOUNT_ID)
            .setSymbol("2330")
            .setExecutionType(executionType)
            .setFillQty("50")
            .setFillPx("100.0000")
            .build();

    assertThat(service.applyMatchingExecution(event)).isSameAs(applied);
    verify(reservationService).applyFill(any(ApplyFillOperation.class));
    verify(applied, never()).status();
  }
}
