package com.simplematch.marketdatapublisher.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.marketdatapublisher.snapshot.MarketSnapshotImportService;
import com.simplematch.marketdatapublisher.snapshot.PreparedMarketSnapshot;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OfflineMarketSnapshotInputsTest {
  private final MarketSnapshotImportService importer =
      new MarketSnapshotImportService(new ObjectMapper());

  @DisplayName(
      "fixture replay and simulator inputs return the same prepared immutable snapshot without network access")
  @Test
  void replayAndSimulatorAreDeterministicAndOffline() {
    final FixtureReplayMarketSnapshotInput replay =
        new FixtureReplayMarketSnapshotInput(
            importer, () -> resource("fixtures/xtai-and-roco-snapshot.json"));
    final PreparedMarketSnapshot firstReplay = replay.nextSnapshot();
    final PreparedMarketSnapshot secondReplay = replay.nextSnapshot();
    final SimulatorMarketSnapshotInput simulator = new SimulatorMarketSnapshotInput(firstReplay);

    assertThat(firstReplay).isEqualTo(secondReplay);
    assertThat(simulator.nextSnapshot()).isEqualTo(firstReplay);
  }

  private InputStream resource(String name) {
    return getClass().getClassLoader().getResourceAsStream(name);
  }
}
