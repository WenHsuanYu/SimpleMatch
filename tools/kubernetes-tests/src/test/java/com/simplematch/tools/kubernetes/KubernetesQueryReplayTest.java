package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Ensures replay cannot pass with missing source partitions or uncommitted consumer progress. */
class KubernetesQueryReplayTest {
  private static final String TOPIC = "matching.events";

  @Test
  void requiresExactKafkaSourceBoundaryAcrossFifteenPartitions() {
    final String complete = IntStream.range(0, 15)
        .mapToObj(partition -> TOPIC + ":" + partition + ":" + (partition + 1))
        .collect(Collectors.joining("\n"));
    final Map<Integer, Long> expected = KubernetesQueryReplay.parseOffsets(TOPIC, complete);
    assertEquals(15, expected.size());
    assertEquals(15L, expected.get(14));
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryReplay.parseOffsets(TOPIC, complete.substring(
            0, complete.lastIndexOf('\n'))));
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryReplay.parseOffsets(TOPIC, complete + "\n" + TOPIC + ":0:99"));
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesQueryReplay.parseOffsets("account.lifecycle", complete));
  }

  @Test
  void consumerMustCommitAllExactOffsetsWithZeroLag() {
    final Map<Integer, Long> expected = IntStream.range(0, 15).boxed()
        .collect(Collectors.toUnmodifiableMap(index -> index, index -> (long) index + 5));
    final String rows = IntStream.range(0, 15)
        .mapToObj(partition -> "query-service-matching-events " + TOPIC + " " + partition
            + " " + (partition + 5) + " " + (partition + 5) + " 0 - - -")
        .collect(Collectors.joining("\n"));
    assertTrue(KubernetesQueryReplay.groupCaughtUp(TOPIC, rows, expected));
    assertTrue(KubernetesQueryReplay.groupAtBoundaries(TOPIC, rows, expected));
    assertFalse(KubernetesQueryReplay.groupCaughtUp(TOPIC,
        rows.replace(TOPIC + " 9 14 14 0", TOPIC + " 9 13 14 1"), expected));
    assertFalse(KubernetesQueryReplay.groupCaughtUp(TOPIC,
        rows.substring(0, rows.lastIndexOf('\n')), expected));
    assertFalse(KubernetesQueryReplay.groupAtBoundaries(TOPIC,
        rows.replace(TOPIC + " 0 5 5 0", TOPIC + " 0 4 5 1"), expected));
  }
}
