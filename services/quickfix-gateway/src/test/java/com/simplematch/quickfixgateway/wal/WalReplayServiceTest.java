package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.risk.RiskSubmissionClient;
import com.simplematch.quickfixgateway.risk.RiskSubmissionResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WalReplayServiceTest {
  @TempDir Path tempDir;

  // Verify that replay sends durable commands back through the idempotent Risk boundary.
  // Scenario: manually write one WAL entry, then run replayAll and inspect the Risk command.
  @DisplayName("the replay service submits stored new orders to Risk")
  @Test
  void replayAllSubmitsStoredNewOrdersToRisk() {
    final WalAppender walAppender =
        new WalAppender(tempDir.resolve("replay.wal"), StandardCharsets.UTF_8);
    walAppender.appendAndFlush(
        new WalRecord(
            new WalMetadata("v1", "cmd-1", 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "GW"),
            new WalOrderReference("O-C1", "C1", "", "ACC-1"),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "AAPL",
                    Side.SIDE_BUY,
                    "10",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("raw")));

    final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
    when(riskSubmissionClient.submitNewOrder(any(OrderCommand.class)))
        .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));

    final WalReplayService replayService = new WalReplayService(walAppender, riskSubmissionClient);
    assertThat(replayService.replayAll()).isEqualTo(1);

    final ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
    verify(riskSubmissionClient).submitNewOrder(captor.capture());
    assertThat(captor.getValue().getCommandId()).isEqualTo("cmd-1");
    assertThat(captor.getValue().getOrderId()).isEqualTo("O-C1");
    assertThat(captor.getValue().getSenderCompId()).isEqualTo("CLIENT");
    assertThat(captor.getValue().getTargetCompId()).isEqualTo("GW");
    assertThat(captor.getValue().getClOrdId()).isEqualTo("C1");
    assertThat(captor.getValue().getSymbol()).isEqualTo("AAPL");
  }

  @DisplayName("the replay service submits cancellation to Risk without order terms")
  @Test
  void replayAllSubmitsCancellationWithoutOrderTerms() throws Exception {
    try (final WalAppender walAppender =
        new WalAppender(tempDir.resolve("cancel-replay.wal"), StandardCharsets.UTF_8)) {
      walAppender.appendAndFlush(
          new WalRecord(
              new WalMetadata("v1", "cmd-cancel", 1L, "quickfix-gateway"),
              new FixSessionIdentity("CLIENT", "GW"),
              new WalOrderReference("O-C1", "CXL-1", "C1", "ACC-1"),
              new WalCommand.Cancel(),
              new RawFixMessage("8=FIX.4.4|35=F")));

      final RiskSubmissionClient riskSubmissionClient = mock(RiskSubmissionClient.class);
      when(riskSubmissionClient.submitCancel(any(OrderCommand.class)))
          .thenReturn(new RiskSubmissionResult("O-C1", true, "", ""));

      final WalReplayService replayService =
          new WalReplayService(walAppender, riskSubmissionClient);
      assertThat(replayService.replayAll()).isEqualTo(1);

      final ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
      verify(riskSubmissionClient).submitCancel(captor.capture());
      assertThat(captor.getValue().getCommandType()).isEqualTo(CommandType.COMMAND_TYPE_CANCEL);
      assertThat(captor.getValue().getOrigClOrdId()).isEqualTo("C1");
      assertThat(captor.getValue().getSymbol()).isEmpty();
      assertThat(captor.getValue().getSide()).isEqualTo(Side.SIDE_UNSPECIFIED);
    }
  }
}
