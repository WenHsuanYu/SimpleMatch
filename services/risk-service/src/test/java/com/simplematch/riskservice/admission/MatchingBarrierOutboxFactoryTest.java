package com.simplematch.riskservice.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.matching.runtime.v1.MatchingCommand;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.riskservice.outbox.OutboxRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that Risk can create one deterministic open or close command for every fixed owner. */
class MatchingBarrierOutboxFactoryTest {
  private final MatchingBarrierOutboxFactory factory =
      new MatchingBarrierOutboxFactory(
          "matching.commands",
          artifact(),
          "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC));

  @DisplayName("open creates fifteen explicit-partition commands with one shared daily identity")
  @Test
  void createsOpenBarriersForEveryPartition() throws Exception {
    final List<OutboxRecord> records = factory.open("2026-08-11-regular");

    assertThat(records).hasSize(15);
    for (int partition = 0; partition < 15; partition++) {
      final OutboxRecord record = records.get(partition);
      final MatchingCommand command = MatchingCommand.parseFrom(record.payloadEnvelope().payload());

      assertThat(record.routing().topic()).isEqualTo("matching.commands");
      assertThat(record.routing().kafkaPartitionId()).isEqualTo(partition);
      assertThat(record.routing().messageKey()).isEqualTo(command.getHeader().getCommandId());
      assertThat(command.getHeader().getPartitionId()).isEqualTo(partition);
      assertThat(command.getOpenBarrier().getExpectedPartitionCount()).isEqualTo(15);
      assertThat(command.getOpenBarrier().getEventSchemaVersion()).isEqualTo(1);
      assertThat(command.getOpenBarrier().getEventIdentityVersion()).isEqualTo(1);
      assertThat(command.getOpenBarrier().getMatchingImageDigest())
          .isEqualTo("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
      assertThat(command.getHeader().getArtifactIdentity().getContentSha256())
          .isEqualTo(artifact().identity().contentSha256());
    }
  }

  @DisplayName("repeated barrier construction is byte-identical and close uses the same partition map")
  @Test
  void createsStableOpenAndCloseBarriers() throws Exception {
    final List<OutboxRecord> firstOpen = factory.open("2026-08-11-regular");
    final List<OutboxRecord> replayOpen = factory.open("2026-08-11-regular");
    final List<OutboxRecord> close = factory.close("2026-08-11-regular");

    assertThat(replayOpen).isEqualTo(firstOpen);
    assertThat(close).hasSize(15);
    assertThat(MatchingCommand.parseFrom(close.get(0).payloadEnvelope().payload()).hasCloseBarrier())
        .isTrue();
    assertThat(close).extracting(record -> record.routing().kafkaPartitionId())
        .containsExactlyElementsOf(firstOpen.stream().map(record -> record.routing().kafkaPartitionId()).toList());
  }

  private static VerifiedMarketReferenceArtifact artifact() {
    try {
      return new MarketReferenceArtifactStartupValidator(new ObjectMapper())
          .validate(
              resource("/market-reference/market_reference.json"),
              new String(resource("/market-reference/market_reference.sha256"), StandardCharsets.US_ASCII)
                  .trim(),
              LocalDate.of(2026, 8, 11));
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static byte[] resource(String path) throws IOException {
    return MatchingBarrierOutboxFactoryTest.class.getResourceAsStream(path).readAllBytes();
  }
}
