package com.simplematch.accountservice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.grpc.AccountReservationV2GrpcService;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import org.junit.jupiter.api.Test;

class RequiredConstructorNullContractTest {

  @Test
  void rejectsNullRequiredDependenciesAtConstruction() {
    assertThatThrownBy(() -> new AccountReservationV2GrpcService(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AccountReservationApplicationService(null, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcAccountAuthorityReader(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcAccountAuthorityLifecycleWriter(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new JdbcAccountOutboxRepository(null))
        .isInstanceOf(NullPointerException.class);
  }
}
