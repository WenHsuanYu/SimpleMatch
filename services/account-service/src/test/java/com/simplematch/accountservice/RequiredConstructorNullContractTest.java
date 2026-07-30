package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.grpc.AccountGrpcService;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.IdempotentReservationService;
import com.simplematch.accountservice.store.JdbcAccountAuthorityRepository;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.accountservice.store.JdbcReservationRepository;
import org.junit.jupiter.api.Test;

class RequiredConstructorNullContractTest {

  @Test
  void rejectsNullRequiredDependenciesAtConstruction() {
    assertThatThrownBy(() -> new AccountGrpcService(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AccountReservationApplicationService(null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new IdempotentReservationService(null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcAccountAuthorityRepository(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcAccountOutboxRepository(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcReservationRepository(null))
        .isInstanceOf(NullPointerException.class);
  }
}
