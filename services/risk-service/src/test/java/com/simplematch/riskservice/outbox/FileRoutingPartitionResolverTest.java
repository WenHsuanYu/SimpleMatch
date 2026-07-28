package com.simplematch.riskservice.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRoutingPartitionResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @DisplayName("published snapshot prefers an explicit partition")
    @Test
    void resolvesConfiguredPartitionFromSnapshot() throws IOException {
        final Path snapshot = writeSnapshot(
                """
                        {
                          "entries": [
                            {"symbol": "AAPL", "routingBucket": "mega-cap", "kafkaPartitionId": 7},
                            {"symbol": "TSLA", "routingBucket": "ev", "kafkaPartitionId": 11}
                          ]
                        }
                        """);

        final RoutingPartitionResolver resolver =
                FileRoutingPartitionResolver.load(objectMapper, snapshot, 15);

        assertThat(resolver.resolve("AAPL")).isEqualTo(7);
        assertThat(resolver.resolve("tsla")).isEqualTo(11);
    }

    @DisplayName("symbols missing from the snapshot fall back to a stable partition")
    @Test
    void fallsBackToStablePartitionWhenSymbolIsMissing() throws IOException {
        final Path snapshot = writeSnapshot(
                """
                        {
                          "entries": [
                            {"symbol": "AAPL", "routingBucket": "mega-cap", "kafkaPartitionId": 7}
                          ]
                        }
                        """);

        final RoutingPartitionResolver resolver =
                FileRoutingPartitionResolver.load(objectMapper, snapshot, 15);

        assertThat(resolver.resolve("MSFT")).isEqualTo(Math.floorMod("MSFT".hashCode(), 15));
    }

    @DisplayName("partitions outside the configured range fail during loading")
    @Test
    void rejectsPartitionOutsideConfiguredRange() throws IOException {
        final Path snapshot = writeSnapshot(
                """
                        {
                          "entries": [
                            {"symbol": "AAPL", "routingBucket": "mega-cap", "kafkaPartitionId": 15}
                          ]
                        }
                        """);

        assertThatThrownBy(() -> FileRoutingPartitionResolver.load(objectMapper, snapshot, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafkaPartitionId outside range");
    }

    private Path writeSnapshot(String content) throws IOException {
        final Path snapshot = tempDir.resolve("orders-validated.snapshot.json");
        Files.writeString(snapshot, content);
        return snapshot;
    }
}