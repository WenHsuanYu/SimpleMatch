package com.simplematch.riskservice.admission;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.orders.v2.NewOrderCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the application-level fast path for an equivalent terminal replay. */
class OrderAdmissionApplicationServiceReplayTest {
  @Test
  void terminalEquivalentReplayReturnsJournaledResultWithoutAccountOrSecondFinalization() {
    final OrderAdmissionValidator validator = mock(OrderAdmissionValidator.class);
    final AdmissionLifecycleTransactions lifecycle = mock(AdmissionLifecycleTransactions.class);
    final AccountReservationClient account = mock(AccountReservationClient.class);
    final AdmissionBackpressurePolicy backpressure = mock(AdmissionBackpressurePolicy.class);
    final AdmissionCommand validated = mock(AdmissionCommand.class);
    final AdmissionResult terminal = mock(AdmissionResult.class);
    final NewOrderCommand request = NewOrderCommand.getDefaultInstance();
    final OrderAdmissionApplicationService service =
        new OrderAdmissionApplicationService(validator, lifecycle, account, backpressure);

    when(validator.validate(request)).thenReturn(validated);
    when(lifecycle.beginAdmission(validated)).thenReturn(terminal);
    when(terminal.state()).thenReturn(AdmissionState.ACCEPTED);

    assertSame(terminal, service.admit(request));

    verify(backpressure).check();
    verify(lifecycle).beginAdmission(validated);
    verify(lifecycle, never()).finalizeAdmission(any(UUID.class), any());
    verifyNoInteractions(account);
  }
}
