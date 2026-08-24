package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.risk.RiskReconciliationClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionID;

/** Verifies WAL recovery restores the canonical order identity used by final Matching Events. */
class WalReplayCanonicalOrderIdentityIntegrationTest {
  private static final String COMMAND_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11";
  private static final String FIX_ORDER_ID = "O-C1";
  private static final String CANONICAL_ORDER_ID = "37e574ea-fecd-336a-bf4a-5afca74e7ac5";
  private static final String ACCOUNT_ID = "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13";

  @TempDir Path tempDir;

  @Test
  void acceptedWalReplayRestoresCanonicalOrderSessionCorrelation() throws Exception {
    try (WalAppender wal =
        new WalAppender(tempDir.resolve("accepted.wal"), StandardCharsets.UTF_8)) {
      final WalRecoveryJournal recovery =
          new WalRecoveryJournal(WalRecoveryJournal.pathFor(wal.walPath()));
      wal.appendAndFlush(newOrder());
      recovery.appendAndFlush(COMMAND_ID, WalRecoveryState.ACCEPTED);
      final RiskSubmissionClient risk = mock(RiskSubmissionClient.class);
      final RiskReconciliationClient reconciliation = mock(RiskReconciliationClient.class);
      final OrderSessionRegistry registry = new OrderSessionRegistry();
      final WalReplayService replay =
          new WalReplayService(
              wal,
              recovery,
              RiskTestSupport.submitter(risk),
              reconciliation,
              registry);

      assertThat(replay.replayAll()).isEqualTo(1);

      verifyNoInteractions(risk, reconciliation);
      assertThat(registry.find(CANONICAL_ORDER_ID))
          .hasValueSatisfying(
              state -> {
                assertThat(state.sessionId())
                    .isEqualTo(new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT"));
                assertThat(state.orderId()).isEqualTo(FIX_ORDER_ID);
                assertThat(state.clOrdId()).isEqualTo("C1");
              });
    }
  }

  private WalRecord newOrder() {
    return new WalRecord(
        new WalMetadata("v1", COMMAND_ID, 1L, "quickfix-gateway"),
        new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
        new WalOrderReference(FIX_ORDER_ID, "C1", "", ACCOUNT_ID),
        new WalCommand.NewOrder(
            new WalOrderTerms(
                "2330",
                Side.SIDE_BUY,
                "10",
                "100",
                OrderType.ORDER_TYPE_LIMIT,
                TimeInForce.TIME_IN_FORCE_ROD)),
        new RawFixMessage("raw"));
  }
}
