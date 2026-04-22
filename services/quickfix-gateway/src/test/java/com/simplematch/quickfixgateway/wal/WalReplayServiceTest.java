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
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WalReplayServiceTest {
  @TempDir
  Path tempDir;

  // 驗證 replay service 會將 WAL 中的既有記錄重新發佈為 OrderCommand。
  // 情境：先手動寫入一筆 WAL，再執行 replayAll 並檢查發佈出去的命令內容。
  @DisplayName("重播服務會將 WAL 指令重新發佈")
  @Test
  void replayAllPublishesStoredOrderCommands() {
    final WalAppender walAppender = new WalAppender(tempDir.resolve("replay.wal"), StandardCharsets.UTF_8);
    walAppender.appendAndFlush(new WalRecord(
        "v1",
        "cmd-1",
        1L,
        "quickfix-gateway",
        "FIX.4.4:CLIENT->GW",
        "D",
        "O-C1",
        "C1",
        "",
        "ACC-1",
        "AAPL",
        Side.SIDE_BUY,
        "10",
        "100",
        OrderType.ORDER_TYPE_LIMIT,
        TimeInForce.TIME_IN_FORCE_ROD,
        CommandType.COMMAND_TYPE_NEW,
        "raw"));

    final OrdersCommandPublisher publisher = mock(OrdersCommandPublisher.class);
    when(publisher.publish(any(OrderCommand.class))).thenReturn(CompletableFuture.completedFuture(null));

    final WalReplayService replayService = new WalReplayService(walAppender, publisher);
    assertThat(replayService.replayAll()).isEqualTo(1);

    final ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
    verify(publisher).publish(captor.capture());
    assertThat(captor.getValue().getCommandId()).isEqualTo("cmd-1");
    assertThat(captor.getValue().getOrderId()).isEqualTo("O-C1");
    assertThat(captor.getValue().getSymbol()).isEqualTo("AAPL");
  }
}