package com.simplematch.quickfixgateway.wal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalRecoveryJournalTest {
  @TempDir Path tempDir;

  @Test
  void latestStateWinsForOneCommand() {
    final Path path = tempDir.resolve("inbound.wal.recovery");
    final WalRecoveryJournal journal = new WalRecoveryJournal(path);
    journal.appendAndFlush("command-1", WalRecoveryState.UNKNOWN);
    journal.appendAndFlush("command-1", WalRecoveryState.PENDING);
    journal.appendAndFlush("command-2", WalRecoveryState.REJECTED);

    assertThat(journal.readLatest())
        .containsEntry("command-1", WalRecoveryState.PENDING)
        .containsEntry("command-2", WalRecoveryState.REJECTED)
        .hasSize(2);
  }

  @Test
  void derivesRecoveryPathBesideCommandWal() {
    assertThat(WalRecoveryJournal.pathFor(tempDir.resolve("inbound.wal")))
        .isEqualTo(tempDir.resolve("inbound.wal.recovery"));
  }
}
