package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalDurableCommandWriterTest {
  @TempDir Path tempDir;

  @Test
  void marksCommandUnknownBeforeRiskSubmissionCanBegin() throws Exception {
    final Path walPath = tempDir.resolve("inbound.wal");
    try (WalAppender walAppender = new WalAppender(walPath, StandardCharsets.UTF_8)) {
      final WalRecoveryJournal recoveryJournal =
          new WalRecoveryJournal(WalRecoveryJournal.pathFor(walPath));
      final WalDurableCommandWriter writer =
          new WalDurableCommandWriter(walAppender, recoveryJournal);
      final WalRecord record = newOrder();

      writer.appendForSubmission(record);

      assertThat(walAppender.readAll()).containsExactly(record);
      assertThat(recoveryJournal.readLatest())
          .containsEntry(record.recordId(), WalRecoveryState.UNKNOWN);
    }
  }

  private WalRecord newOrder() {
    return new WalRecord(
        new WalMetadata(
            "v1",
            "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c11",
            1L,
            "quickfix-gateway"),
        new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
        new WalOrderReference(
            "O-C1", "C1", "", "0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c13"),
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
