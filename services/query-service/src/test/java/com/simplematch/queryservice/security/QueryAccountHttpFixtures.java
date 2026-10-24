package com.simplematch.queryservice.security;

import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import com.simplematch.queryservice.store.QueryProjectionSource;
import java.util.UUID;

/** Keeps synthetic account identities stable across independent V1 and V2 HTTP policy contexts. */
final class QueryAccountHttpFixtures {
  static final QuerySecurityFixtures POLICY = new QuerySecurityFixtures();
  static final String X = POLICY.bind().principals().getFirst().accountGrants().getFirst();
  static final String Y = POLICY.bind().principals().get(1).accountGrants().getFirst();

  private QueryAccountHttpFixtures() {}

  static void projectAccounts(QueryProjectionApplicationService projections) {
    projectAccount(projections, X, 1000, 0);
    projectAccount(projections, Y, 2000, 1);
  }

  static void projectAccount(
      QueryProjectionApplicationService projections, String accountId, long notional, long offset) {
    final AccountLifecycleEvent event = AccountLifecycleEvent.newBuilder()
        .setMetadata(EventMetadata.newBuilder().setSchemaVersion("v2")
            .setEventId(UUID.randomUUID().toString()).setCreatedAtUnixMs(1000)
            .setSourceService("account-service"))
        .setAccountId(accountId)
        .setOrderId(UUID.randomUUID().toString())
        .setReservationId(UUID.randomUUID().toString())
        .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(notional))
        .build();
    projections.projectAccountLifecycle(
        event, event.toByteArray(), new QueryProjectionSource("account.lifecycle", 0, offset, 1000));
  }
}
