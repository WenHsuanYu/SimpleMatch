package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simplematch.contracts.common.v1.OrderType;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.common.v1.TimeInForce;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WalReplayServiceTest {
  @TempDir Path tempDir;

  // Verify that the replay service republishes existing WAL records as OrderCommand messages.
  // Scenario: manually write one WAL entry, then run replayAll and inspect the published command.
  @DisplayName("the replay service republishes WAL commands")
  @Test
  void replayAllPublishesStoredOrderCommands() {
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

    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    when(publisher.publish(any(OrderCommand.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    final WalReplayService replayService = new WalReplayService(walAppender, publisher);
    assertThat(replayService.replayAll()).isEqualTo(1);

    final ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
    verify(publisher).publish(captor.capture());
    assertThat(captor.getValue().getCommandId()).isEqualTo("cmd-1");
    assertThat(captor.getValue().getOrderId()).isEqualTo("O-C1");
    assertThat(captor.getValue().getSenderCompId()).isEqualTo("CLIENT");
    assertThat(captor.getValue().getTargetCompId()).isEqualTo("GW");
    assertThat(captor.getValue().getClOrdId()).isEqualTo("C1");
    assertThat(captor.getValue().getSymbol()).isEqualTo("AAPL");
  }
}
